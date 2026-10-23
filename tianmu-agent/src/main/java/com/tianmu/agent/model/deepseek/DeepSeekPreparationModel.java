package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.PreparationModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import java.util.Map;
import static com.tianmu.agent.model.deepseek.StructuredModelOutput.*;

final class DeepSeekPreparationModel implements PreparationModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String emotionPrompt, understandingPrompt;
    DeepSeekPreparationModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;
        String common = "\n\n" + prompts.text("common.current-focus-boundary") + "\n\n" + prompts.text("common.json-output");
        emotionPrompt = prompts.text("preparation.emotion") + common;
        understandingPrompt = prompts.text("preparation.understanding") + common;
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
    public Understanding understand(Input input, CallControl control) {
        JsonNode result = call(understandingPrompt, input, control, config.preparationMaxTokens());
        fields(result, "needsClarification", "clarificationQuestion", "conditions", "rewrittenQuery");
        if (!result.path("needsClarification").isBoolean() || !result.path("conditions").isObject())
            throw new IllegalArgumentException("Invalid understanding structure");
        for (String key : new String[]{"clarificationQuestion", "rewrittenQuery"})
            if (!result.path(key).isNull() && !result.path(key).isTextual())
                throw new IllegalArgumentException("Invalid understanding text");
        Map<String, Object> conditions = mapper.convertValue(result.path("conditions"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        return new Understanding(result.path("needsClarification").booleanValue(),
                result.path("clarificationQuestion").textValue(), conditions, result.path("rewrittenQuery").textValue());
    }
}
