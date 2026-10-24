package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentTypes.ToolDefinition;
import com.tianmu.agent.resource.ResourceFiles;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ToolCatalog {
    private final Map<String, ToolDefinition> definitions;
    public ToolCatalog() { this(ResourceFiles.discover("classpath*:tools/*/tool.json")); }
    public ToolCatalog(Resource[] resources) {
        Map<String, ToolDefinition> loaded = new TreeMap<>();
        for (Resource resource : resources) {
            ToolDefinition definition = ResourceFiles.json(resource, ToolDefinition.class);
            validate(definition);
            if (loaded.putIfAbsent(definition.name(), definition) != null)
                throw new IllegalStateException("Duplicate tool descriptor: " + definition.name());
        }
        if (loaded.isEmpty()) throw new IllegalStateException("Tool catalog is empty");
        definitions = Collections.unmodifiableMap(loaded);
    }
    static void validate(ToolDefinition d) {
        if (!ResourceFiles.name(d.name()) || d.description() == null || d.description().isBlank())
            throw new IllegalStateException("Invalid tool descriptor");
        Map<String, Object> schema = d.inputSchema();
        if (!schema.keySet().equals(Set.of("type", "properties", "required", "additionalProperties"))
                || !"object".equals(schema.get("type")) || !Boolean.FALSE.equals(schema.get("additionalProperties"))
                || !(schema.get("properties") instanceof Map<?, ?> properties) || !(schema.get("required") instanceof List<?> required)
                || required.size() != new HashSet<>(required).size() || !properties.keySet().containsAll(required))
            throw new IllegalStateException("Tool schemas must declare required fields and reject additional properties");
        for (Object spec : properties.values()) {
            if (!(spec instanceof Map<?, ?> field) || !Set.of("string", "integer").contains(field.get("type"))
                    || !Set.of("type", "minimum", "maximum", "minLength", "maxLength", "format", "enum").containsAll(field.keySet())
                    || field.containsKey("format") && !"date".equals(field.get("format")))
                throw new IllegalStateException("Unsupported tool schema field");
            if (field.containsKey("minimum") && (!(field.get("minimum") instanceof Number) || !"integer".equals(field.get("type")))
                    || field.containsKey("maximum") && (!(field.get("maximum") instanceof Number) || !"integer".equals(field.get("type")))
                    || field.containsKey("minLength") && (!(field.get("minLength") instanceof Integer n) || n < 0 || !"string".equals(field.get("type")))
                    || field.containsKey("maxLength") && (!(field.get("maxLength") instanceof Integer n) || n < 0 || !"string".equals(field.get("type")))
                    || field.containsKey("format") && !"string".equals(field.get("type")))
                throw new IllegalStateException("Invalid tool schema constraint");
            if (field.containsKey("minimum") && field.containsKey("maximum")
                    && new java.math.BigDecimal(field.get("minimum").toString()).compareTo(new java.math.BigDecimal(field.get("maximum").toString())) > 0
                    || field.containsKey("minLength") && field.containsKey("maxLength")
                    && ((Number) field.get("minLength")).intValue() > ((Number) field.get("maxLength")).intValue())
                throw new IllegalStateException("Inverted tool schema bounds");
            if (field.containsKey("enum") && (!(field.get("enum") instanceof List<?> choices) || choices.isEmpty()
                    || choices.stream().anyMatch(value -> "string".equals(field.get("type")) ? !(value instanceof String) : !(value instanceof Number))))
                throw new IllegalStateException("Invalid tool schema enum");
        }
    }
    public Map<String, ToolDefinition> all() { return definitions; }
}
