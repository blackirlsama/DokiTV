package com.tianmu.agent.core;

import java.math.*;
import java.util.*;

/** Tool/schema payloads cross extension boundaries: defensive copies must include nested containers. */
public final class ImmutablePayload {
    private ImmutablePayload() {}
    public static Map<String, Object> map(Map<String, ?> source) {
        return copyMap(Objects.requireNonNull(source), 0);
    }
    private static Map<String, Object> copyMap(Map<?, ?> source, int depth) {
        if (depth > 20) throw new IllegalArgumentException("Payload nesting too deep");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) throw new IllegalArgumentException("Payload keys must be strings");
            copy.put(text, copy(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }
    private static Object copy(Object value, int depth) {
        if (depth > 20) throw new IllegalArgumentException("Payload nesting too deep");
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigDecimal || value instanceof BigInteger) return value;
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        if (value instanceof Map<?, ?> map) return copyMap(map, depth);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(copy(item, depth + 1)));
            return Collections.unmodifiableList(copy);
        }
        // These framework records have immutable members and defensive constructors.
        if (value instanceof AgentTypes.Skill || value instanceof AgentTypes.ToolDefinition
                || value instanceof AgentTypes.MetricData || value instanceof DanmakuTypes.Dataset) return value;
        throw new IllegalArgumentException("Unsupported mutable payload value");
    }
}
