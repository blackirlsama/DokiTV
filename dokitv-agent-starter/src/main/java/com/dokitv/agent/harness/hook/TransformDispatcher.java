package com.dokitv.agent.harness.hook;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.harness.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TransformDispatcher {
    private record Registered(TransformHook<?> hook, String name, TransformPoint<?> point, int order) {}
    private final List<Registered> hooks;
    private final Tracer tracer;
    private final ObjectMapper mapper;
    public TransformDispatcher(List<TransformHook<?>> hooks, Tracer tracer, ObjectMapper mapper) {
        Set<String> names = new HashSet<>();
        List<Registered> registered = new ArrayList<>();
        for (TransformHook<?> hook : hooks) {
            if (hook.name() == null || !hook.name().matches("[a-z][a-z0-9_.-]{0,63}")
                    || !names.add(hook.name()) || hook.point() == null)
                throw new IllegalArgumentException("Invalid or duplicate transform hook registration");
            registered.add(new Registered(hook, hook.name(), hook.point(), hook.order()));
        }
        this.hooks = registered.stream().sorted(Comparator.comparingInt(Registered::order).thenComparing(Registered::name)).toList();
        this.tracer = tracer;
        this.mapper = mapper;
    }
    public <T> T apply(TransformPoint<T> point, T input, TransformContext context, Runnable checkpoint) {
        TransformInvariants.validate(point, input, input);
        bounded(input, 1024 * 1024);
        T current = input;
        for (Registered hook : hooks) {
            if (hook.point() != point) continue;
            checkpoint.run();
            Span span = tracer.spanBuilder("hook.transform_" + point.name()).startSpan();
            TraceAttributes.apply(span, Map.of("run.id", context.runId().toString(), "hook.name", hook.name(),
                    "hook.point", point.name(), "hook.mode", "TRANSFORM"));
            try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, context.runId().toString())) {
                T next;
                try { next = invoke(hook.hook(), point, current, context); }
                catch (HookRejectedException e) { throw new AgentException("HOOK_REJECTED", "Transform rejected operation"); }
                catch (AgentException e) {
                    if (e.code().equals("HOOK_TRANSFORM_INVALID")) throw e;
                    throw new AgentException("HOOK_TRANSFORM_FAILED", "Transform hook failed");
                }
                catch (Exception e) {
                    span.setAttribute("exception.type", e.getClass().getSimpleName());
                    throw new AgentException("HOOK_TRANSFORM_FAILED", "Transform hook failed");
                }
                TransformInvariants.validate(point, input, next);
                bounded(next, 1024 * 1024);
                current = next;
            } catch (AgentException e) {
                span.setStatus(StatusCode.ERROR);
                TraceAttributes.apply(span, Map.of("hook.failed", true, "error.code", e.code()));
                throw e;
            } finally { span.end(); }
            checkpoint.run();
        }
        checkpoint.run();
        bounded(current, point == TransformPoint.CONTEXT ? 256 * 1024 : 64 * 1024);
        return current;
    }
    private void bounded(Object value, int bytes) {
        try {
            if (mapper.writeValueAsBytes(value).length > bytes)
                throw new AgentException("HOOK_PAYLOAD_TOO_LARGE", "Transform payload exceeds harness limit");
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw new AgentException("HOOK_TRANSFORM_INVALID", "Transform payload cannot be serialized"); }
    }
    @SuppressWarnings("unchecked")
    private static <T> T invoke(TransformHook<?> hook, TransformPoint<T> point, T value, TransformContext context) {
        Object result = ((TransformHook<T>) hook).transform(value, context);
        if (result == null || !point.type().isInstance(result))
            throw new AgentException("HOOK_TRANSFORM_INVALID", "Transform returned an invalid type");
        return point.type().cast(result);
    }
}
