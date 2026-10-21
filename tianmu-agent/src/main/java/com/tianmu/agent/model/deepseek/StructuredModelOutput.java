package com.tianmu.agent.model.deepseek;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Protocol validation stays in Java; editable prompt content lives under resources/prompts. */
final class StructuredModelOutput {
    private StructuredModelOutput() {}
    static void fields(JsonNode node, String... names) {
        Set<String> expected = Set.of(names), actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected)) throw new IllegalArgumentException("Invalid structured model output");
    }
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException("Missing model field");
        return value.textValue();
    }
    static List<Map<String, String>> messages(String instruction, Object input, ObjectMapper mapper) {
        try { return List.of(Map.of("role", "system", "content", instruction),
                Map.of("role", "user", "content", mapper.writeValueAsString(input))); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot serialize model input"); }
    }
}
