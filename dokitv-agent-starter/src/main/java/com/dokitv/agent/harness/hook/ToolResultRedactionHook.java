package com.dokitv.agent.harness.hook;

import com.dokitv.agent.core.AgentTypes.ToolResult;
import org.springframework.stereotype.Component;
import java.util.*;

/** Example structured redactor, not a complete PII detector. Original evidence is not rewritten. */
@Component
public class ToolResultRedactionHook implements TransformHook<ToolResult> {
    private static final Set<String> SECRET_KEYS = Set.of("authorization", "token", "accesstoken", "refreshtoken",
            "password", "secret", "apikey", "sasljaasconfig");
    public String name() { return "redact-tool-credentials"; }
    public TransformPoint<ToolResult> point() { return TransformPoint.TOOL_RESULT; }
    public int order() { return -100; }
    public ToolResult transform(ToolResult input, TransformContext context) {
        return input.withData(redactMap(input.data()));
    }
    private Map<String, Object> redactMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = key.toString();
            String canonical = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            result.put(name, SECRET_KEYS.contains(canonical) ? "[REDACTED]" : redact(value));
        });
        return result;
    }
    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) return redactMap(map);
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }
}
