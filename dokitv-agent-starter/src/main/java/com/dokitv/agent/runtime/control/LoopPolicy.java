package com.dokitv.agent.runtime.control;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

@ConfigurationProperties("harness.loop")
public record LoopPolicy(@DefaultValue("6") int maxTurns,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("5s") Duration modelTimeout,
        @DefaultValue("5s") Duration toolTimeout,
        @DefaultValue("3s") Duration finalizeTimeout,
        @DefaultValue("3") int consecutiveToolFailures,
        @DefaultValue("true") boolean finalizeEnabled,
        @DefaultValue("16") int maxModelCalls) {
    public LoopPolicy(int turns, Duration request, Duration model, Duration tool, Duration finalizeTimeout, int failures, boolean finalizeEnabled) {
        this(turns, request, model, tool, finalizeTimeout, failures, finalizeEnabled, 16);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public LoopPolicy {
        if (maxTurns < 1 || maxTurns > 100 || consecutiveToolFailures < 1 || consecutiveToolFailures > 10 || maxModelCalls < 1 || maxModelCalls > 500)
            throw new IllegalArgumentException("Invalid loop limits");
        for (Duration timeout : new Duration[]{requestTimeout, modelTimeout, toolTimeout, finalizeTimeout})
            if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(10)) > 0)
                throw new IllegalArgumentException("Timeout must be between 1ms and 10min");
    }
    public static LoopPolicy defaults() {
        return new LoopPolicy(6, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(3), 3, true);
    }
}
