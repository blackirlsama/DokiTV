package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentTypes.Snapshot;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.RunState;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

/** One session per run; terminal hooks are exactly once, including queued cancellation/rejection. */
public final class HarnessSession {
    private final RunState run;
    private final HookDispatcher hooks;
    private final Span root;
    private final Context context;
    private final Tracer tracer;
    private final TraceUsage usage = new TraceUsage();
    private final AtomicBoolean begun = new AtomicBoolean();
    private final AtomicBoolean observedTerminal = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicInteger workers = new AtomicInteger();
    private volatile Snapshot terminal;
    private volatile Instant terminalAt;

    HarnessSession(RunState run, Tracer tracer, HookDispatcher hooks) {
        this.run = run;
        this.hooks = hooks;
        this.tracer = tracer;
        root = tracer.spanBuilder("agent.run").startSpan();
        context = Context.current().with(root).with(TraceUsage.KEY, usage);
        TraceAttributes.apply(root, TraceSemantics.identity(run));
        TraceAttributes.apply(root, Map.of("run.id", run.id.toString(), "input.char_count", run.message.length()));
    }
    public String traceId() { return root.getSpanContext().getTraceId(); }
    public Context context() { return context; }
    void begin() {
        if (!begun.compareAndSet(false, true)) return;
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
            marker(SpanType.RECEIVE_QUERY, Map.of("input.char_count", run.message.length()));
            fire(HookPoint.REQUEST_START, Map.of("input.char_count", run.message.length()));
        } finally { run.onTerminal(this::terminal); }
    }
    void execute(Runnable operation) {
        workers.incrementAndGet();
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
            operation.run();
        } finally { workers.decrementAndGet(); finishIfIdle(); }
    }
    void fire(HookPoint point, Map<String, Object> attributes) {
        Span current = Span.current();
        hooks.fire(new HookEvent("1.0", point, run.id, traceId(), current.getSpanContext().getSpanId(),
                run.snapshot().steps(), Instant.now(), TraceAttributes.sanitize(attributes)));
    }
    void marker(SpanType type, Map<String, Object> attributes) {
        Context parent = Span.current().getSpanContext().getTraceId().equals(traceId()) ? Context.current() : context;
        Span span = tracer.spanBuilder(type.value()).setParent(parent).startSpan();
        try {
            TraceAttributes.apply(span, TraceSemantics.identity(run));
            TraceAttributes.apply(span, Map.of("span.type", type.value(), "loop.step", run.snapshot().steps()));
            TraceAttributes.apply(span, attributes);
            if (type == SpanType.ERROR) span.setStatus(StatusCode.ERROR);
        } finally { span.end(); }
    }
    private void terminal(Snapshot snapshot) {
        if (!observedTerminal.compareAndSet(false, true)) return;
        terminalAt = Instant.now();
        // Notification can happen on a cancellation HTTP thread; bind the run's captured context explicitly.
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
            if (snapshot.status() == Status.CANCELLED) fire(HookPoint.ON_CANCEL, Map.of("run.status", "CANCELLED"));
            if (snapshot.status() == Status.FAILED) {
                Map<String, Object> error = Map.of("error.code", snapshot.errorCode() == null ? "UNKNOWN" : snapshot.errorCode());
                marker(SpanType.ERROR, error);
                fire(HookPoint.ON_ERROR, error);
            }
        } finally { terminal = snapshot; finishIfIdle(); }
    }
    private void finishIfIdle() {
        Snapshot snapshot = terminal;
        if (snapshot == null || workers.get() != 0 || !ended.compareAndSet(false, true)) return;
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
            TraceAttributes.apply(root, Map.of("run.status", snapshot.status().name(), "run.steps", snapshot.steps(),
                    "run.stop_reason", snapshot.stopReason().name(), "run.model_calls", snapshot.modelCalls(),
                    "run.preparation_calls", snapshot.preparationCalls(), "run.total_model_calls", snapshot.totalModelCalls()));
            Map<String, Object> finish = new HashMap<>(usage.totals());
            finish.put("usage.complete", ((Number) finish.get("usage.reported_calls")).longValue() == snapshot.totalModelCalls());
            finish.put("finish.type", TraceSemantics.finishType(snapshot));
            finish.put("run.status", snapshot.status().name());
            finish.put("run.stop_reason", snapshot.stopReason().name());
            long elapsed = Math.max(0, java.time.Duration.between(run.createdAt, terminalAt).toMillis());
            finish.put("run.e2e_ms", elapsed);
            if (snapshot.status() == Status.SUCCEEDED || snapshot.status() == Status.NEEDS_INPUT)
                finish.put("run.answer_ready_ms", elapsed);
            if (snapshot.answer() != null) finish.put("answer.char_count", snapshot.answer().text().length());
            TraceAttributes.apply(root, finish);
            if (snapshot.route() != null) TraceAttributes.apply(root, Map.of("request.route", snapshot.route().name(),
                    "preparation.emotion", snapshot.preparation().emotion().name(),
                    "preparation.degraded_count", snapshot.preparation().degradedStages().size(),
                    "preparation.skipped_count", snapshot.preparation().skippedStages().size()));
            if (snapshot.faqId() != null) TraceAttributes.apply(root, Map.of("faq.id", snapshot.faqId(), "faq.version", snapshot.faqVersion()));
            if (snapshot.status() == Status.FAILED) root.setStatus(StatusCode.ERROR);
            fire(HookPoint.REQUEST_END, Map.of("run.status", snapshot.status().name(), "run.steps", snapshot.steps()));
            marker(SpanType.FINISH, finish);
        } finally { root.end(); }
    }
}
