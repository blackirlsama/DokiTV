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
    public ToolDefinition describe(ToolDefinition declared, SkillCatalog skills) {
        Map<String, Object> schema = new LinkedHashMap<>(declared.inputSchema());
        schema.put("properties", Map.of("name", Map.of("type", "string", "enum", skills.names().stream().sorted().toList())));
        return new ToolDefinition(name(), declared.description(), schema);
    }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Skill skill = context.activateSkill(ToolArguments.text(call, "name"));
        return new ToolResult(name(), Map.of("skill", skill, "tool_schemas", context.definitionsForSkill(skill.name())), null);
    }
}
