package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Deliberate small schema subset; unsupported declaration types fail during catalog loading. */
public final class ToolArguments {
    private ToolArguments() {}
    public static String text(ToolCall call, String name) {
        Object value = call.arguments().get(name);
        if (!(value instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }
    public static long integer(ToolCall call, String name) {
        Object value = call.arguments().get(name);
        if (!(value instanceof Number)) throw invalid();
        return new BigDecimal(value.toString()).longValueExact();
    }
    public static void validate(ToolCall call, ToolDefinition definition) {
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");
        if (!call.arguments().keySet().equals(properties.keySet())) throw invalid();
        properties.forEach((key, spec) -> {
            String name = key.toString(); Map<?, ?> field = (Map<?, ?>) spec;
            if ("integer".equals(field.get("type"))) {
                long value = integer(call, name);
                if (field.containsKey("minimum") && BigDecimal.valueOf(value).compareTo(new BigDecimal(field.get("minimum").toString())) < 0) throw invalid();
            } else {
                String value = text(call, name);
                if (field.containsKey("minLength") && value.length() < ((Number) field.get("minLength")).intValue()) throw invalid();
                if ("date".equals(field.get("format"))) LocalDate.parse(value);
            }
            if (field.containsKey("enum") && !((List<?>) field.get("enum")).contains(call.arguments().get(name))) throw invalid();
        });
    }
    private static AgentException invalid() { return new AgentException("INVALID_ARGUMENTS", "Tool arguments do not match the declared schema"); }
}
