package com.tianmu.agent.harness.hook;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import java.util.*;

/** Non-pluggable invariants are checked after every transform, not merely after the entire chain. */
final class TransformInvariants {
    private TransformInvariants() {}
    private static final Set<String> FACT_KEYS = Set.of("video_id", "date", "metric", "value", "metric_version",
            "source", "data_as_of", "complete", "demo", "evidence_id", "skill", "tool_schemas", "error_code", "retryable", "attempt", "analysis", "sampling_method");
    static void require(boolean condition) {
        if (!condition) throw new AgentException("HOOK_TRANSFORM_INVALID", "Transform violated a harness invariant");
    }
    static <T> void validate(TransformPoint<T> point, T baseline, T value) {
        require(value != null && point.type().isInstance(value));
        if (value instanceof String text) require(!text.isBlank() && text.length() <= 8000);
        if (value instanceof Answer answer) {
            require(answer.text() != null && !answer.text().isBlank() && answer.text().length() <= 8000);
            require(answer.evidenceIds().equals(((Answer) baseline).evidenceIds()));
        }
        if (value instanceof Decision decision) {
            require(decision.kind() != null);
            switch (decision.kind()) {
                case TOOL -> require(decision.call() != null && decision.answer() == null && decision.clarification() == null);
                case ANSWER -> require(decision.answer() != null && decision.call() == null && decision.clarification() == null);
                case CLARIFY -> require(decision.clarification() != null && !decision.clarification().isBlank()
                        && decision.call() == null && decision.answer() == null);
            }
        }
        if (value instanceof ToolCall call) {
            require(call.name() != null && call.name().equals(((ToolCall) baseline).name()));
        }
        if (value instanceof ToolResult result) result((ToolResult) baseline, result);
        if (value instanceof ModelContext context) {
            ModelContext original = (ModelContext) baseline;
            require(context.scope().equals(original.scope()) && context.activeSkills().equals(original.activeSkills())
                    && context.remainingSteps() == original.remainingSteps() && context.businessDate().equals(original.businessDate())
                    && context.message().equals(original.message()) && context.finalizing() == original.finalizing()
                    && Objects.equals(context.systemDirective(), original.systemDirective())
                    && Objects.equals(context.preparation(), original.preparation()) && Objects.equals(context.task(), original.task()) && Objects.equals(context.memory(), original.memory()));
            require(new HashSet<>(context.availableTools()).size() == context.availableTools().size()
                    && original.availableTools().containsAll(context.availableTools()));
            Set<Integer> used = new HashSet<>();
            for (ToolResult observation : context.observations()) {
                int matching = -1;
                for (int i = 0; i < original.observations().size(); i++) {
                    if (used.contains(i)) continue;
                    try { result(original.observations().get(i), observation); matching = i; break; }
                    catch (AgentException ignored) {}
                }
                require(matching >= 0);
                used.add(matching);
            }
        }
    }
    private static void result(ToolResult original, ToolResult transformed) {
        require(Objects.equals(original.tool(), transformed.tool()) && Objects.equals(original.evidenceId(), transformed.evidenceId()));
        require(Objects.equals(original.terminalAnswer(), transformed.terminalAnswer()));
        for (String key : FACT_KEYS) {
            require(original.data().containsKey(key) == transformed.data().containsKey(key));
            if (original.data().containsKey(key)) require(Objects.equals(original.data().get(key), transformed.data().get(key)));
        }
    }
}
