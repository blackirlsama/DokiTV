package com.dokitv.agent.harness;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.harness.trace.*;
import com.dokitv.agent.runtime.RunState;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;
import com.dokitv.agent.runtime.control.*;

@Component
public class RuntimeHarness implements AutoCloseable {
    private final Tracer tracer;
    private final HookDispatcher hooks;
    private final TransformDispatcher transforms;
    private final LoopControl loopControl;
    private final boolean ownsLoopControl;
    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeHarness(Tracer tracer, HookDispatcher hooks, TransformDispatcher transforms, LoopControl control) {
        this(tracer, hooks, transforms, control, false);
    }
    public RuntimeHarness(Tracer tracer, HookDispatcher hooks, TransformDispatcher transforms) {
        this(tracer, hooks, transforms, new LoopControl(LoopPolicy.defaults()), true);
    }
    private RuntimeHarness(Tracer tracer, HookDispatcher hooks, TransformDispatcher transforms, LoopControl control, boolean owns) {
        this.tracer = tracer; this.hooks = hooks; this.transforms = transforms;
        this.loopControl = control; this.ownsLoopControl = owns;
    }
    public RuntimeHarness(Tracer tracer, HookDispatcher hooks) {
        this(tracer, hooks, new TransformDispatcher(List.of(), tracer,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
    }
    public <T> T transform(RunState run, TransformPoint<T> point, T input) {
        HarnessSession session = start(run);
        return transforms.apply(point, input, new TransformContext("1.0", run.id, session.traceId(),
                run.snapshot().steps()), run::checkpoint);
    }
    public HarnessSession start(RunState run) {
        HarnessSession session = run.harnessSession(() -> new HarnessSession(run, tracer, hooks));
        loopControl.arm(run);
        session.begin();
        return session;
    }
    public void execute(RunState run, Runnable operation) { start(run).execute(operation); }
    public LoopPolicy policy() { return loopControl.policy(); }
    public <T> T modelCall(RunState run, boolean finalizing, Function<CallControl, T> operation) {
        run.modelCallStarted();
        return loopControl.call(run, finalizing ? policy().finalizeTimeout() : policy().modelTimeout(),
                "MODEL_CALL_TIMEOUT", "MODEL_ERROR", this::contextual, operation);
    }
    public <T> T toolCall(RunState run, Function<CallControl, T> operation) {
        return loopControl.call(run, policy().toolTimeout(), "TOOL_CALL_TIMEOUT", "TOOL_ERROR", this::contextual, operation);
    }
    @Override public void close() { if (ownsLoopControl) loopControl.close(); }
    public <T> T stage(RunState run, String name, HookPoint before, HookPoint after,
                       Map<String, Object> attributes, Supplier<T> operation) {
        HarnessSession session = start(run);
        Context parent = Span.current().getSpanContext().getTraceId().equals(session.traceId())
                ? Context.current() : session.context();
        Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
        TraceAttributes.apply(span, attributes);
        TraceAttributes.apply(span, Map.of("run.id", run.id.toString(), "loop.step", run.snapshot().steps()));
        boolean executed = false;
        String outcome = "success";
        String error = "";
        try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, run.id.toString())) {
            try {
                run.checkpoint();
                if (before != null) session.fire(before, attributes);
                run.checkpoint();
                executed = true;
                return operation.get();
            } catch (RuntimeException e) {
                outcome = "error";
                error = e instanceof AgentException agent ? agent.code() : "INTERNAL_ERROR";
                span.setStatus(StatusCode.ERROR);
                TraceAttributes.apply(span, Map.of("error.code", error, "exception.type", e.getClass().getSimpleName()));
                throw e;
            } finally {
                Map<String, Object> result = new HashMap<>(attributes);
                result.put("operation.outcome", outcome);
                result.put("operation.executed", executed);
                if (!error.isEmpty()) result.put("error.code", error);
                TraceAttributes.apply(span, result);
                if (after != null) session.fire(after, result);
            }
        } finally { span.end(); }
    }
    public Runnable contextual(Runnable operation) {
        Context captured = Context.current();
        Map<String, String> capturedMdc = org.slf4j.MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
            try (Scope scope = captured.makeCurrent()) {
                if (capturedMdc == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(capturedMdc);
                operation.run();
            } finally {
                if (previous == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previous);
            }
        };
    }
}
