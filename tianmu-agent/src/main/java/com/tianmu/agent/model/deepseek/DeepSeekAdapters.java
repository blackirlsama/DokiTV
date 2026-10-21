package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.preparation.PreparationModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@Profile("deepseek")
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAdapters {
    @Bean DeepSeekClient deepSeekClient(DeepSeekProperties properties, ObjectMapper mapper) { return new DeepSeekClient(properties, mapper); }
    @Bean PreparationModelPort deepSeekPreparation(DeepSeekClient client, DeepSeekProperties properties, ObjectMapper mapper, PromptCatalog prompts) {
        return new DeepSeekPreparationModel(client, properties, mapper, prompts);
    }
    @Bean ModelPort deepSeekHarnessModel(DeepSeekClient client, DeepSeekProperties properties, ObjectMapper mapper, PromptCatalog prompts) {
        return new DeepSeekHarnessModel(client, properties, mapper, prompts);
    }
}
