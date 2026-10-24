package com.tianmu.agent.preparation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.preparation")
public record PreparationPolicy(@DefaultValue("300ms") Duration faqTimeout,
                                @DefaultValue("2s") Duration understandingTimeout,
                                @DefaultValue("300ms") Duration emotionTimeout,
                                @DefaultValue("2s") Duration intentTimeout) {
    public PreparationPolicy(Duration faq, Duration understanding, Duration emotion) { this(faq, understanding, emotion, understanding); }
    public PreparationPolicy(Duration faq, Duration understanding) { this(faq, understanding, understanding); }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public PreparationPolicy {
        for (Duration value : new Duration[]{faqTimeout, understandingTimeout, emotionTimeout, intentTimeout})
            if (value == null || value.toMillis() < 1 || value.compareTo(Duration.ofSeconds(30)) > 0)
                throw new IllegalArgumentException("Preparation timeout must be 1ms..30s");
    }
}
