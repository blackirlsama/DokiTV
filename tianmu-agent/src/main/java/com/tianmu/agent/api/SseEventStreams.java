package com.tianmu.agent.api;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.events.RunEventStore;
import com.tianmu.agent.harness.trace.*;
import io.opentelemetry.api.trace.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Slow network clients never execute under a task-state lock or on an Agent worker. */
@Component
public class SseEventStreams implements AutoCloseable {
    private final Tracer tracer;
    public SseEventStreams() { this(io.opentelemetry.api.OpenTelemetry.noop().getTracer("tianmu.sse")); }
    @org.springframework.beans.factory.annotation.Autowired
    public SseEventStreams(Tracer tracer) { this.tracer = tracer; }
    private final Semaphore capacity = new Semaphore(64);
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor delivery = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> { Thread t = new Thread(task, "agent-sse"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    protected SseEmitter newEmitter() { return new SseEmitter(60000L); }
    public SseEmitter subscribe(RunState run, long after) {
        run.events().after(after);
        if (!capacity.tryAcquire()) throw new AgentException("CAPACITY_EXCEEDED", "SSE subscriber limit reached");
        SseEmitter emitter;
        try { emitter = newEmitter(); }
        catch (RuntimeException e) { capacity.release(); throw e; }
        Subscription subscription = new Subscription(run, after, emitter);
        subscriptions.add(subscription);
        try { subscription.attach(); return subscription.emitter; }
        catch (RuntimeException e) { subscription.stop(); throw e; }
    }
    private final class Subscription {
        private final RunEventStore store;
        private final SseEmitter emitter;
        private final AtomicBoolean stopped = new AtomicBoolean(), scheduled = new AtomicBoolean();
        private volatile RunEventStore.Registration registration;
        private final Span span;
        private final long subscribedAt = System.nanoTime();
        private final java.time.Instant createdAt;
        private final AtomicBoolean firstContent = new AtomicBoolean();
        private volatile boolean complete;
        private long cursor;
        Subscription(RunState run, long cursor, SseEmitter emitter) {
            this.store = run.events(); this.cursor = cursor; this.emitter = emitter; this.createdAt = run.createdAt;
            span = tracer.spanBuilder(SpanType.TERMINAL_DELIVERY.value()).startSpan();
            TraceNode.begin(span, SpanType.TERMINAL_DELIVERY.value());
            TraceAttributes.apply(span, TraceSemantics.identity(run));
            TraceAttributes.apply(span, Map.of("span.type", SpanType.TERMINAL_DELIVERY.value(), "delivery.type", "SSE",
                    "delivery.replay", cursor > 0 || com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status())));
            if (run.snapshot().traceId() != null) TraceAttributes.apply(span, Map.of("run.trace_id", run.snapshot().traceId()));
        }
        void attach() {
            emitter.onCompletion(this::stop);
            emitter.onTimeout(() -> { stop(); emitter.complete(); });
            emitter.onError(error -> stop());
            registration = store.onChange(this::signal);
            if (stopped.get()) registration.close(); else signal();
        }
        void signal() {
            if (stopped.get() || !scheduled.compareAndSet(false, true)) return;
            try { delivery.execute(this::drain); }
            catch (RejectedExecutionException ignored) { stop(); }
        }
        void drain() {
            try {
                while (!stopped.get()) {
                    RunEventStore.Batch batch = store.after(cursor);
                    for (var event : batch.events()) {
                        if (stopped.get()) return;
                        emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type()).data(event));
                        if (Set.of("ANSWER", "CLARIFICATION", "ERROR").contains(event.type()) && firstContent.compareAndSet(false, true))
                            TraceAttributes.apply(span, Map.of("delivery.first_content_ms",
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - subscribedAt),
                                    "delivery.e2e_ms", Math.max(0, java.time.Duration.between(createdAt, java.time.Instant.now()).toMillis())));
                        cursor = event.sequence();
                    }
                    if (batch.closed()) { complete = true; emitter.complete(); stop(); return; }
                    scheduled.set(false);
                    RunEventStore.Batch latest = store.after(cursor);
                    if (latest.lastSequence() > cursor || latest.closed()) signal();
                    return;
                }
            } catch (Exception ignored) { stop(); }
        }
        void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            TraceAttributes.apply(span, Map.of("delivery.success", complete));
            TraceAttributes.apply(span, Map.of("node.status", complete ? "SUCCEEDED" : "CANCELLED"));
            if (!complete) span.setStatus(StatusCode.ERROR);
            span.end();
            if (registration != null) registration.close();
            subscriptions.remove(this); capacity.release();
        }
    }
    @Override @PreDestroy public void close() { subscriptions.forEach(Subscription::stop); delivery.shutdownNow(); }
}
