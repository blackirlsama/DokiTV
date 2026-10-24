package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.resource.TextPage;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ReadResultTool implements AgentTool {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public String name() { return "read_result"; }
    public int order() { return 30; }
    public boolean requiresSkill() { return false; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Evidence evidence = context.evidence(ToolArguments.text(call, "result_id"));
        final String body;
        try { body = JSON.writeValueAsString(evidence.facts()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new com.tianmu.agent.core.AgentException("INVALID_EVIDENCE", "Cannot serialize evidence");
        }
        if (call.arguments().containsKey("offset") || call.arguments().containsKey("limit")
                || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) {
            int offset = call.arguments().containsKey("offset") ? Math.toIntExact(ToolArguments.integer(call, "offset")) : 0;
            int limit = call.arguments().containsKey("limit") ? Math.toIntExact(ToolArguments.integer(call, "limit")) : 2048;
            return new ToolResult(name(), Map.of("evidence_id", evidence.id(), "format", "json",
                    "page", TextPage.read(body, offset, limit)), evidence.id());
        }
        return new ToolResult(name(), Map.of(evidence.analysis() == null ? "metric" : "analysis", evidence.facts()), evidence.id());
    }
}
