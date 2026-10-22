package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.ZoneId;
import java.util.Set;

@ConfigurationProperties("agent.bridge")
public record BridgeProperties(@DefaultValue("tianmu") String tenantId,
        Set<String> activeUserStates, @DefaultValue("Asia/Shanghai") String datetimeStorageZone,
        @DefaultValue("2") int queryTimeoutSeconds) {
    public BridgeProperties {
        if (tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,64}") || activeUserStates == null || activeUserStates.isEmpty())
            throw new IllegalArgumentException("Configure tenantId and the database's active user states");
        activeUserStates = Set.copyOf(activeUserStates);
        ZoneId.of(datetimeStorageZone);
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 10) throw new IllegalArgumentException("Invalid query timeout");
    }
}
