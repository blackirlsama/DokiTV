package com.tianmu.agent.harness.trace;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import java.util.*;

/** Structured outcomes only. Raw messages, arguments, results and reasoning are never exported here. */
public final class TraceSemantics {
    private TraceSemantics() {}
    public static Map<String, Object> identity(com.tianmu.agent.runtime.RunState run) {
        return Map.of("run.id", run.id.toString(), "session.id", run.sessionId.toString(),
                "run.turn_seq", run.turnSeq, "tenant.id", run.owner.tenantId(), "actor.id", run.owner.userId());
    }
    public static Map<String, Object> result(Object value) {
        Map<String, Object> attrs = new HashMap<>();
        if (value instanceof Decision decision && decision.kind() != null) {
            attrs.put("model.decision", switch (decision.kind()) {
                case TOOL -> "CALL_TOOL"; case ANSWER -> "FINAL_ANSWER"; case CLARIFY -> "CLARIFY";
            });
            if (decision.call() != null) attrs.put("model.selected_tool", decision.call().name());
            if (decision.answer() != null && decision.answer().text() != null)
                attrs.put("output.char_count", decision.answer().text().length());
        } else if (value instanceof Result prepared) {
            attrs.put("request.route", prepared.route().name());
            attrs.put("route.reason", switch (prepared.route()) {
                case FAQ -> "FAQ_HIT"; case CLARIFY -> "CLARIFICATION_REQUIRED"; case HARNESS -> "READY";
            });
            attrs.put("preparation.emotion", prepared.request().emotion().name());
            attrs.put("preparation.intent", prepared.request().intent().name());
            attrs.put("preparation.degraded_count", prepared.request().degradedStages().size());
            if (prepared.faq() != null) attrs.putAll(faq(prepared.faq()));
        } else if (value instanceof Emotion emotion) {
            attrs.put("preparation.emotion", emotion.name());
        } else if (value instanceof Intent intent) {
            attrs.put("preparation.intent", intent.name());
        } else if (value instanceof Understanding understood) {
            attrs.put("model.decision", understood.needsClarification() ? "CLARIFY" : "PROCEED");
            attrs.put("query.rewritten", understood.rewrittenQuery() != null);
        } else if (value instanceof Optional<?> optional) {
            attrs.put("faq.hit", optional.filter(FaqAnswer.class::isInstance).isPresent());
            optional.filter(FaqAnswer.class::isInstance).map(FaqAnswer.class::cast).ifPresent(f -> attrs.putAll(faq(f)));
        } else if (value instanceof ToolResult tool) {
            attrs.put("tool.success", !tool.data().containsKey("error_code"));
            attrs.put("tool.has_evidence", tool.evidenceId() != null);
            attrs.put("tool.terminal_delivery", tool.terminalAnswer() != null);
        } else if (value instanceof ModelContext model) {
            attrs.put("agent.available_tools", model.availableTools().stream().map(ToolDefinition::name).limit(32).toList());
            attrs.put("agent.available_tool_count", model.availableTools().size());
            attrs.put("context.observation_count", model.observations().size());
        }
        return attrs;
    }
    public static Map<String, Object> faq(FaqAnswer faq) {
        return Map.of("faq.hit", true, "faq.id", faq.id(), "faq.version", faq.version());
    }
    public static String finishType(Snapshot snapshot) {
        return switch (snapshot.status()) {
            case SUCCEEDED -> snapshot.stopReason().name();
            case NEEDS_INPUT -> "CLARIFICATION";
            case CANCELLED -> snapshot.stopReason().name();
            case FAILED -> snapshot.stopReason().name();
            default -> "INCOMPLETE";
        };
    }
}
