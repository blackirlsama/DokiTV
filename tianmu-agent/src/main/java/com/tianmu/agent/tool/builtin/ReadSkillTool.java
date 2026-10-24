package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ReadSkillTool implements AgentTool {
    public String name() { return "read_skill"; }
    public int order() { return 20; }
    public boolean requiresSkill() { return false; }
    public boolean canActivateSkills() { return true; }
    @SuppressWarnings("unchecked")
    public ToolDefinition describe(ToolDefinition declared, SkillCatalog skills) {
        Map<String, Object> schema = new LinkedHashMap<>(declared.inputSchema());
        Map<String, Object> properties = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        properties.put("name", Map.of("type", "string", "enum", skills.names().stream().sorted().toList()));
        schema.put("properties", properties);
        return new ToolDefinition(name(), declared.description(), schema);
    }
    public ToolResult execute(ToolCall call, ToolContext context) {
        String skillName = ToolArguments.text(call, "name");
        if (call.arguments().containsKey("resource")) {
            int offset = call.arguments().containsKey("offset") ? Math.toIntExact(ToolArguments.integer(call, "offset")) : 0;
            int limit = call.arguments().containsKey("limit") ? Math.toIntExact(ToolArguments.integer(call, "limit")) : 2048;
            return new ToolResult(name(), Map.of("skill_name", skillName, "resource", ToolArguments.text(call, "resource"),
                    "page", context.skillResource(skillName, ToolArguments.text(call, "resource"), offset, limit)), null);
        }
        if (call.arguments().containsKey("offset") || call.arguments().containsKey("limit"))
            throw new com.tianmu.agent.core.AgentException("INVALID_ARGUMENTS", "Paging requires a declared skill reference");
        Skill skill = context.activateSkill(skillName);
        return new ToolResult(name(), Map.of("skill", skill, "tool_schemas", context.definitionsForSkill(skill.name()),
                "resources", context.skillResources(skillName)), null);
    }
}
