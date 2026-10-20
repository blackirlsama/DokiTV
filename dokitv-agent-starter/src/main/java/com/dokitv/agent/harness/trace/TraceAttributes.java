package com.dokitv.agent.harness.trace;

import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.Span;
import java.util.*;

/** Export allowlist is the final boundary, including for attributes added by third-party hooks. */
public final class TraceAttributes {
    private TraceAttributes() {}
    private static final Set<String> ALLOWED = Set.of("run.id", "run.status", "run.steps", "run.stop_reason", "run.model_calls", "run.finalizing", "loop.step",
            "tool.name", "skill.name", "skill.version", "hook.name", "hook.point", "hook.mode",
            "hook.failed", "operation.executed", "operation.outcome", "error.code", "exception.type",
            "input.char_count", "context.observation_count", "answer.evidence_count", "http.method", "http.route", "http.status_code");
    public static Map<String, Object> sanitize(Map<String, ?> values) {
        Map<String, Object> clean = new TreeMap<>();
        values.forEach((key, value) -> {
            if (!ALLOWED.contains(key) || value == null) return;
            if (value instanceof Number || value instanceof Boolean) clean.put(key, value);
            else if (value instanceof String text && text.length() <= 160 && text.matches("[A-Za-z0-9_./:@ -]*"))
                clean.put(key, text);
        });
        return Map.copyOf(clean);
    }
    public static void apply(Span span, Map<String, ?> attributes) {
        sanitize(attributes).forEach((key, value) -> {
            if (value instanceof Number number) span.setAttribute(key, number.longValue());
            else if (value instanceof Boolean bool) span.setAttribute(key, bool);
            else span.setAttribute(key, value.toString());
        });
    }
    public static Map<String, Object> from(Attributes attributes) {
        Map<String, Object> values = new HashMap<>();
        attributes.forEach((key, value) -> values.put(key.getKey(), value));
        return sanitize(values);
    }
}
