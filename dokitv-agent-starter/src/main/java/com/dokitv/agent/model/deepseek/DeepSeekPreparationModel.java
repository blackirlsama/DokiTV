package com.dokitv.agent.model.deepseek;

import com.dokitv.agent.core.PreparationTypes.*;
import com.dokitv.agent.preparation.PreparationModelPort;
import com.dokitv.agent.prompt.PromptCatalog;
import com.dokitv.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import java.util.Map;
import static com.dokitv.agent.model.deepseek.StructuredModelOutput.*;

final class DeepSeekPreparationModel implements PreparationModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String emotionPrompt, clarificationPrompt, rewritePrompt;
    DeepSeekPreparationModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;
        String common = "\n\n" + prompts.text("common.current-focus-boundary") + "\n\n" + prompts.text("common.json-output");
        emotionPrompt = prompts.text("preparation.emotion") + common;
        clarificationPrompt = prompts.text("preparation.clarification") + common;
        rewritePrompt = prompts.text("preparation.rewrite") + common;
    }
    private JsonNode call(String prompt, Input input, CallControl control, int tokens) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("memory", input.memory()); payload.put("query", input.query()); payload.put("businessDate", input.businessDate().toString());
        return client.complete(config.preparationModel(), messages(prompt, payload, mapper), tokens, control);
    }
    public Emotion emotion(Input input, CallControl control) {
        JsonNode result = call(emotionPrompt, input, control, 128);
        fields(result, "emotion"); return Emotion.valueOf(text(result, "emotion"));
    }
    public Clarification clarify(Input input, CallControl control) {
        JsonNode result = call(clarificationPrompt, input, control, config.preparationMaxTokens());
        fields(result, "required", "question");
        if (!result.path("required").isBoolean()) throw new IllegalArgumentException("Invalid clarification flag");
        return new Clarification(result.path("required").booleanValue(), result.path("question").isTextual() ? result.path("question").textValue() : null);
    }
    public String rewrite(Input input, CallControl control) {
        JsonNode result = call(rewritePrompt, input, control, config.preparationMaxTokens());
        fields(result, "query"); return text(result, "query");
    }
}
