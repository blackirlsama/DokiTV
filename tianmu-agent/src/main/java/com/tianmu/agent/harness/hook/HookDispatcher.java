package com.tianmu.agent.harness.hook;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

@Component
public class HookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);
    private record Registered(AgentHook hook, String name, AgentHook.Mode mode, Set<HookPoint> points, int order) {}
    private final List<Registered> hooks;
    private final Tracer tracer;
    private final LongAdder observerFailures = new LongAdder();
    public HookDispatcher(List<AgentHook> hooks, Tracer tracer) {
        Set<String> names = new HashSet<>();
        for (AgentHook hook : hooks) {
            if (hook.name() == null || !hook.name().matches("[a-z][a-z0-9_.-]{0,63}") || !names.add(hook.name()))
                throw new IllegalArgumentException("Hook names must be unique safe identifiers");
            if (hook.points() == null || hook.points().isEmpty() || hook.mode() == null)
                throw new IllegalArgumentException("Hook points and mode required");
            if (hook.mode() == AgentHook.Mode.GATE && hook.points().stream().anyMatch(point -> !point.gateAllowed()))
                throw new IllegalArgumentException("Gates can only subscribe to pre-execution points");
        }
        this.hooks = hooks.stream().map(hook -> new Registered(hook, hook.name(), hook.mode(), Set.copyOf(hook.points()), hook.order()))
                .sorted(Comparator.comparingInt(Registered::order).thenComparing(Registered::name)).toList();
        this.tracer = tracer;
    }
    public void fire(HookEvent event) {
        for (Registered hook : hooks) {
            if (!hook.points().contains(event.point())) continue;
            Span span = tracer.spanBuilder("hook." + event.point().name().toLowerCase(Locale.ROOT)).startSpan();
            TraceNode.begin(span, "hook." + event.point().name().toLowerCase(Locale.ROOT));
            TraceAttributes.apply(span, event.attributes());
            TraceAttributes.apply(span, Map.of("run.id", event.runId().toString(), "hook.name", hook.name(),
                    "hook.point", event.point().name(), "hook.mode", hook.mode().name()));
            try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, event.runId().toString())) {
                TraceDetails.input(event);
                hook.hook().onEvent(event);
                TraceDetails.output(Map.of("completed", true));
                TraceNode.success(span);
            } catch (Exception e) {
                span.setStatus(StatusCode.ERROR);
                TraceAttributes.apply(span, Map.of("node.status", "FAILED"));
                TraceAttributes.apply(span, Map.of("hook.failed", true, "exception.type", e.getClass().getSimpleName()));
                if (hook.mode() == AgentHook.Mode.GATE) throw new AgentException("HOOK_REJECTED", "A harness gate rejected this operation");
                observerFailures.increment();
                log.warn("hook={} point={} observer_failed type={}", hook.name(), event.point(), e.getClass().getSimpleName());
            } finally { span.end(); }
        }
    }
    public long observerFailureCount() { return observerFailures.sum(); }
}
