package com.dokitv.agent.tool.builtin;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ReadResultTool implements AgentTool {
    public String name() { return "read_result"; }
    public int order() { return 30; }
    public boolean requiresSkill() { return false; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Evidence evidence = context.evidence(ToolArguments.text(call, "result_id"));
        return new ToolResult(name(), Map.of("metric", evidence.data()), evidence.id());
    }
}
