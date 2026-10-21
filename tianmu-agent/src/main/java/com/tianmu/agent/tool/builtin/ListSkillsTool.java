package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ListSkillsTool implements AgentTool {
    public String name() { return "list_skills"; }
    public int order() { return 10; }
    public boolean requiresSkill() { return false; }
    public ToolResult execute(ToolCall call, ToolContext context) { return new ToolResult(name(), Map.of("skills", context.listSkills()), null); }
}
