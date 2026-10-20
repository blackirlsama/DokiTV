package com.dokitv.agent.harness;

import com.dokitv.agent.core.AgentTypes.Snapshot;
import com.dokitv.agent.core.AgentTypes.Status;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.harness.trace.*;
import com.dokitv.agent.runtime.RunState;
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
    private final AtomicBoolean begun = new AtomicBoolean();
    private final AtomicBoolean observedTerminal = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicInteger workers = new AtomicInteger();
    private volatile Snapshot terminal;

    HarnessSession(RunState run, Tracer tracer, HookDispatcher hooks) {
        this.run = run;
        this.hooks = hooks;
        root = tracer.spanBuilder("agent.run").startSpan();
        context = Context.current().with(root);
        TraceAttributes.apply(root, Map.of("run.id", run.id.toString(), "input.char_count", run.message.length()));
    }
    public String traceId() { return root.getSpanContext().getTraceId(); }
    public Context context() { return context; }
    void begin() {
        if (!begun.compareAndSet(false, true)) return;
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
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
    private void terminal(Snapshot snapshot) {
        if (!observedTerminal.compareAndSet(false, true)) return;
        // Notification can happen on a cancellation HTTP thread; bind the run's captured context explicitly.
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
            if (snapshot.status() == Status.CANCELLED) fire(HookPoint.ON_CANCEL, Map.of("run.status", "CANCELLED"));
            if (snapshot.status() == Status.FAILED) fire(HookPoint.ON_ERROR,
                    Map.of("error.code", snapshot.errorCode() == null ? "UNKNOWN" : snapshot.errorCode()));
        } finally { terminal = snapshot; finishIfIdle(); }
    }
    private void finishIfIdle() {
        Snapshot snapshot = terminal;
        if (snapshot == null || workers.get() != 0 || !ended.compareAndSet(false, true)) return;
        try (Scope scope = context.makeCurrent(); TraceLogScope mdc = new TraceLogScope(root, run.id.toString())) {
            TraceAttributes.apply(root, Map.of("run.status", snapshot.status().name(), "run.steps", snapshot.steps(),
                    "run.stop_reason", snapshot.stopReason().name(), "run.model_calls", snapshot.modelCalls()));
            if (snapshot.status() == Status.FAILED) root.setStatus(StatusCode.ERROR);
            fire(HookPoint.REQUEST_END, Map.of("run.status", snapshot.status().name(), "run.steps", snapshot.steps()));
        } finally { root.end(); }
    }
}
