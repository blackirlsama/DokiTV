package com.tianmu.agent.harness.trace;

import com.tianmu.agent.core.AgentException;
import io.opentelemetry.api.trace.Span;
import java.util.*;

/** UI taxonomy and business state, distinct from OTel's OK / ERROR / UNSET. */
public final class TraceNode {
    private TraceNode() {}
    public static String type(String name) {
        if (name.equals("agent.run")) return "REQUEST";
        if (name.startsWith("http.")) return "HTTP";
        if (name.startsWith("hook.")) return "HOOK";
        if (name.contains("model") || name.matches("agent.prepare.(intent|emotion|understanding)")) return "MODEL";
        if (name.equals("agent.tool")) return "TOOL";
        if (name.equals("agent.skill")) return "SKILL";
        if (name.contains("context") || name.contains("assemble") || name.contains("memory")) return "CONTEXT";
        if (name.contains("deliver") || name.contains("answer_ready")) return "DELIVERY";
        if (name.contains("route") || name.contains("preparation") || name.contains("faq")) return "ROUTING";
        if (name.contains("loop") || name.contains("finaliz")) return "LOOP";
        return "STAGE";
    }
    public static void begin(Span span, String name) {
        String type = type(name);
        TraceAttributes.apply(span, Map.of("node.type", type, "node.status", "RUNNING", "detail.input_state", "NOT_CAPTURED", "detail.output_state", "NOT_CAPTURED"));
        if (type.equals("MODEL")) TraceAttributes.apply(span, Map.of("model.usage_state", "NOT_REPORTED", "model.first_token_state", "NOT_REPORTED", "execution.attempt", 1, "execution.retry_count", 0));
    }
    public static void success(Span span) { TraceAttributes.apply(span, Map.of("node.status", "SUCCEEDED")); }
    public static void failure(Span span, RuntimeException error) {
        String code = error instanceof AgentException a ? a.code() : "INTERNAL_ERROR";
        String status = code.contains("TIMEOUT") || code.contains("DEADLINE") ? "TIMED_OUT" : code.contains("CANCEL") ? "CANCELLED" : "FAILED";
        TraceAttributes.apply(span, Map.of("node.status", status, "error.code", code, "exception.type", error.getClass().getSimpleName(),
                "execution.retryable", Set.of("TOOL_CALL_TIMEOUT", "TOOL_TEMPORARY_ERROR").contains(code)));
        try (var scope = span.makeCurrent()) {
            TraceDetails.capture("error", Map.of("code", code, "type", error.getClass().getSimpleName(), "message", error.getMessage() == null ? "" : error.getMessage()), "ERROR");
        }
    }
}
