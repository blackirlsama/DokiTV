package com.dokitv.agent.tool;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.skill.SkillCatalog;

/** One trusted Java handler per tool. Descriptions and input schemas live in resources/tools. */
public interface AgentTool {
    String name();
    default int order() { return 100; }
    default boolean requiresSkill() { return true; }
    default boolean canActivateSkills() { return false; }
    default ToolDefinition describe(ToolDefinition declared, SkillCatalog skills) { return declared; }
    ToolResult execute(ToolCall call, ToolContext context);
}
