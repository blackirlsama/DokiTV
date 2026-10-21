package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.model.deepseek.StructuredModelOutput.*;

final class DeepSeekHarnessModel implements ModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String systemPrompt, commonPrompt, focusPrompt;
    DeepSeekHarnessModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;
        focusPrompt = prompts.text("common.current-focus-boundary"); systemPrompt = prompts.text("harness.system"); commonPrompt = prompts.text("common.json-output");
    }
    public Decision decide(ModelContext context) {
        return decide(context, new CallControl(Instant.now().plusSeconds(30), new CancellationToken()));
    }
    public Decision decide(ModelContext context, CallControl control) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("memory", context.memory()); input.put("query", context.message()); input.put("businessDate", context.businessDate().toString());
        input.put("tools", context.availableTools()); input.put("results", context.observations());
        input.put("activeSkills", context.activeSkills()); input.put("remainingSteps", context.remainingSteps());
        input.put("preparation", context.preparation()); input.put("finalizing", context.finalizing());
        if (context.task() != null) input.put("taskSpecification", context.task().specification());
        String prompt = systemPrompt + "\n\n" + focusPrompt + (context.finalizing() ? "\n\n" + context.systemDirective() : "") + "\n\n" + commonPrompt;
        JsonNode result = client.complete(config.harnessModel(), messages(prompt, input, mapper), config.harnessMaxTokens(), control);
        try {
            return switch (text(result, "kind")) {
                case "TOOL" -> {
                    fields(result, "kind", "call"); JsonNode call = result.path("call"); fields(call, "name", "arguments");
                    if (!call.path("arguments").isObject()) throw new IllegalArgumentException("Invalid tool arguments");
                    yield Decision.tool(text(call, "name"), mapper.convertValue(call.path("arguments"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                }
                case "CLARIFY" -> { fields(result, "kind", "clarification"); yield Decision.clarify(text(result, "clarification")); }
                case "ANSWER" -> {
                    fields(result, "kind", "answer"); JsonNode answer = result.path("answer"); fields(answer, "text", "evidenceIds");
                    if (!answer.path("evidenceIds").isArray()) throw new IllegalArgumentException("Invalid evidence IDs");
                    List<String> ids = new ArrayList<>();
                    for (JsonNode id : answer.path("evidenceIds")) { if (!id.isTextual()) throw new IllegalArgumentException("Invalid evidence ID"); ids.add(id.textValue()); }
                    yield Decision.answer(text(answer, "text"), ids);
                }
                default -> throw new IllegalArgumentException("Unknown decision");
            };
        } catch (RuntimeException e) { throw new AgentException("INVALID_DECISION", "Invalid structured model decision"); }
    }
}
