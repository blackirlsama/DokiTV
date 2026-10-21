package com.tianmu.agent.api;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.events.RunEventStore;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Slow network clients never execute under a task-state lock or on an Agent worker. */
@Component
public class SseEventStreams implements AutoCloseable {
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
        Subscription subscription = new Subscription(run.events(), after, emitter);
        subscriptions.add(subscription);
        try { subscription.attach(); return subscription.emitter; }
        catch (RuntimeException e) { subscription.stop(); throw e; }
    }
    private final class Subscription {
        private final RunEventStore store;
        private final SseEmitter emitter;
        private final AtomicBoolean stopped = new AtomicBoolean(), scheduled = new AtomicBoolean();
        private volatile RunEventStore.Registration registration;
        private long cursor;
        Subscription(RunEventStore store, long cursor, SseEmitter emitter) { this.store = store; this.cursor = cursor; this.emitter = emitter; }
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
                        cursor = event.sequence();
                    }
                    if (batch.closed()) { emitter.complete(); stop(); return; }
                    scheduled.set(false);
                    RunEventStore.Batch latest = store.after(cursor);
                    if (latest.lastSequence() > cursor || latest.closed()) signal();
                    return;
                }
            } catch (Exception ignored) { stop(); }
        }
        void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            if (registration != null) registration.close();
            subscriptions.remove(this); capacity.release();
        }
    }
    @Override @PreDestroy public void close() { subscriptions.forEach(Subscription::stop); delivery.shutdownNow(); }
}
