package com.tianmu.agent.adapters.business;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;

@ConfigurationProperties("agent.business")
public record BusinessProperties(URI baseUrl, @DefaultValue("3000") int timeoutMillis) {
    public BusinessProperties {
        if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getFragment() != null)
            throw new IllegalArgumentException("Configure a business service URL without credentials");
        boolean local = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost());
        if (!"https".equals(baseUrl.getScheme()) && !(local && "http".equals(baseUrl.getScheme())))
            throw new IllegalArgumentException("Business service requires HTTPS outside loopback");
        if (timeoutMillis < 100 || timeoutMillis > 10000) throw new IllegalArgumentException("Invalid business timeout");
    }
}
