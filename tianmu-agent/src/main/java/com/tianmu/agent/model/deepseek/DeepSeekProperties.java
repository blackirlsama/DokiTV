package com.tianmu.agent.model.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("agent.models")
public record DeepSeekProperties(@DefaultValue("https://api.deepseek.com") URI baseUrl,
        String apiKey, @DefaultValue("deepseek-flash") String preparationModel,
        @DefaultValue("deepseek-v4-pro") String harnessModel,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("384") int preparationMaxTokens, @DefaultValue("1536") int harnessMaxTokens) {
    public DeepSeekProperties {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DEEPSEEK_API_KEY is required");
        boolean local = SetHolder.LOCAL.contains(baseUrl.getHost());
        if ((!"https".equals(baseUrl.getScheme()) && !(local && "http".equals(baseUrl.getScheme())))
                || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getFragment() != null)
            throw new IllegalArgumentException("A secure model base URL is required");
        for (String model : new String[]{preparationModel, harnessModel})
            if (model == null || !model.matches("[a-zA-Z0-9_.:-]{1,100}")) throw new IllegalArgumentException("Invalid model ID");
        if (connectTimeout == null || connectTimeout.toMillis() < 1 || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || preparationMaxTokens < 64 || preparationMaxTokens > 2048 || harnessMaxTokens < 128 || harnessMaxTokens > 8192)
            throw new IllegalArgumentException("Invalid model call limits");
    }
    private static class SetHolder { static final java.util.Set<String> LOCAL = java.util.Set.of("localhost", "127.0.0.1", "[::1]"); }
    @Override public String toString() { return "DeepSeekProperties[preparationModel=" + preparationModel + ", harnessModel=" + harnessModel + ", apiKey=REDACTED]"; }
}
