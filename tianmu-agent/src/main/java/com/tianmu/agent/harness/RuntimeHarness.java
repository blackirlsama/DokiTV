package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.RunState;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;
import com.tianmu.agent.runtime.control.*;

@Component
public class RuntimeHarness implements AutoCloseable {
    private final Tracer tracer;
    private final HookDispatcher hooks;
    private final TransformDispatcher transforms;
    private final LoopControl loopControl;
    private final boolean ownsLoopControl;
    private TraceDetails traceDetails;
    private final Map<UUID, HarnessSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeHarness(Tracer tracer, HookDispatcher hooks, TransformDispatcher transforms, LoopControl control, TraceDetails details) {
        this(tracer, hooks, transforms, control, false);
        this.traceDetails = details;
    }
    public RuntimeHarness(Tracer tracer, HookDispatcher hooks, TransformDispatcher transforms, LoopControl control) { this(tracer, hooks, transforms, control, false); }
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
        HarnessSession session = sessions.computeIfAbsent(run.id, id -> new HarnessSession(run, tracer, hooks, traceDetails));
        run.traceId(session.traceId());
        loopControl.arm(run);
        session.begin();
        return session;
    }
    public void execute(RunState run, Runnable operation) { start(run).execute(operation); }
    public LoopPolicy policy() { return loopControl.policy(); }
    public void marker(RunState run, SpanType type, Map<String, Object> attributes) {
        start(run).marker(type, attributes);
    }
    public <T> T modelCall(RunState run, boolean finalizing, Function<CallControl, T> operation) {
        run.modelCallStarted();
        return loopControl.call(run, finalizing ? policy().finalizeTimeout() : policy().modelTimeout(),
                "MODEL_CALL_TIMEOUT", "MODEL_ERROR", this::contextual, operation);
    }
    public <T> T toolCall(RunState run, Function<CallControl, T> operation) {
        return loopControl.call(run, policy().toolTimeout(), "TOOL_CALL_TIMEOUT", "TOOL_ERROR", this::contextual, operation);
    }
    public void forget(UUID id) { sessions.remove(id); }
    @Override public void close() { if (ownsLoopControl) loopControl.close(); sessions.clear(); }
    public <T> T stage(RunState run, String name, HookPoint before, HookPoint after,
                       Map<String, Object> attributes, Supplier<T> operation) {
        HarnessSession session = start(run);
        Context parent = Span.current().getSpanContext().getTraceId().equals(session.traceId())
                ? Context.current() : session.context();
        Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
        TraceNode.begin(span, name);
        TraceAttributes.apply(span, attributes);
        TraceAttributes.apply(span, TraceSemantics.identity(run));
        SpanType type = SpanType.forStage(name);
        if (type != null) TraceAttributes.apply(span, Map.of("span.type", type.value()));
        TraceAttributes.apply(span, Map.of("run.id", run.id.toString(), "loop.step", run.snapshot().steps()));
        boolean executed = false;
        String outcome = "success";
        String error = "";
        Map<String, Object> resultAttributes = new HashMap<>();
        try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, run.id.toString())) {
            try {
                run.checkpoint();
                if (before != null) session.fire(before, attributes);
                run.checkpoint();
                executed = true;
                T value = operation.get();
                TraceDetails.output(value);
                TraceNode.success(span);
                if (!(value instanceof Optional<?>) || type == SpanType.FAQ_LOOKUP)
                    resultAttributes.putAll(TraceSemantics.result(value));
                return value;
            } catch (RuntimeException e) {
                TraceNode.failure(span, e);
                outcome = "error";
                error = e instanceof AgentException agent ? agent.code() : "INTERNAL_ERROR";
                span.setStatus(StatusCode.ERROR);
                TraceAttributes.apply(span, Map.of("error.code", error, "exception.type", e.getClass().getSimpleName()));
                if (type == SpanType.TOOL_CALL) resultAttributes.put("tool.success", false);
                if (type == SpanType.LLM_INFERENCE) resultAttributes.put("model.decision", "ERROR");
                throw e;
            } finally {
                Map<String, Object> result = new HashMap<>(attributes);
                result.putAll(resultAttributes);
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
