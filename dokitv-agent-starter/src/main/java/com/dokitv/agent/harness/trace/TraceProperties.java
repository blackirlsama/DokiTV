package com.dokitv.agent.harness.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.nio.file.Path;

@ConfigurationProperties("harness.trace")
public record TraceProperties(@DefaultValue("file") String sink,
        @DefaultValue("./var/trace") Path directory,
        @DefaultValue("10000") int maxPendingFiles,
        @DefaultValue("67108864") long maxPendingBytes,
        @DefaultValue("65536") int maxRecordBytes,
        @DefaultValue("5242880") long archiveMaxBytes,
        @DefaultValue("3") int archiveBackups,
        @DefaultValue Kafka kafka) {
    public record Kafka(@DefaultValue("") String bootstrapServers,
            @DefaultValue("dokitv.agent.trace.v1") String topic,
            @DefaultValue("PLAINTEXT") String securityProtocol,
            @DefaultValue("") String saslMechanism,
            @DefaultValue("") String saslJaasConfig) {}
}
