package com.dokitv.agent.preparation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.preparation")
public record PreparationPolicy(@DefaultValue("300ms") Duration faqTimeout,
                                @DefaultValue("2s") Duration parallelTimeout,
                                @DefaultValue("1500ms") Duration rewriteTimeout,
                                @DefaultValue("300ms") Duration emotionTimeout) {
    public PreparationPolicy(Duration faq, Duration parallel) { this(faq, parallel, parallel, parallel); }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PreparationPolicy {
        for (Duration value : new Duration[]{faqTimeout, parallelTimeout, rewriteTimeout, emotionTimeout})
            if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofSeconds(30)) > 0)
                throw new IllegalArgumentException("Preparation timeout must be 1ms..30s");
    }
}
