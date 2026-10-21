package com.tianmu.agent.harness.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.util.*;

@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfiguration {
    @Bean TraceExportStats traceExportStats() { return new TraceExportStats(); }
    private TraceSink configuredSink(TraceProperties properties) throws Exception {
        if ("file".equals(properties.sink())) return new JsonlTraceSink(
                properties.directory().resolve("archive"), properties.archiveMaxBytes(), properties.archiveBackups());
        if (!"kafka".equals(properties.sink())) throw new IllegalArgumentException("Unknown trace sink");
        var kafka = properties.kafka();
        if (kafka.bootstrapServers().isBlank()) throw new IllegalArgumentException("Kafka bootstrap servers required");
        if (kafka.topic() == null || !kafka.topic().matches("[A-Za-z0-9._-]{1,249}")
                || kafka.topic().equals(".") || kafka.topic().equals("..")) throw new IllegalArgumentException("Invalid trace topic");
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "tianmu-agent-trace-" + UUID.randomUUID());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 8 * 1024 * 1024L);
        config.put("security.protocol", kafka.securityProtocol());
        if (!kafka.saslMechanism().isBlank()) config.put("sasl.mechanism", kafka.saslMechanism());
        if (!kafka.saslJaasConfig().isBlank()) config.put("sasl.jaas.config", kafka.saslJaasConfig());
        return new KafkaTraceSink(new KafkaProducer<>(config), kafka.topic());
    }
    @Bean(destroyMethod = "") OutboxSpanExporter spanExporter(TraceProperties properties, ObjectProvider<TraceSink> customSinks,
            ObjectMapper mapper, TraceExportStats stats) throws Exception {
        TraceSink custom = customSinks.getIfAvailable();
        TraceSink sink = custom == null ? configuredSink(properties) : custom;
        try {
            TraceSpool spool = new TraceSpool(properties.directory(), properties.maxPendingFiles(),
                    properties.maxPendingBytes(), properties.maxRecordBytes());
            return new OutboxSpanExporter(spool, sink, mapper, stats);
        } catch (Exception e) {
            if (custom == null) sink.close();
            throw e;
        }
    }
    @Bean(destroyMethod = "close") SdkTracerProvider harnessTracerProvider(OutboxSpanExporter exporter) {
        return SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).setResource(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "tianmu-operations-agent")))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    }
    @Bean Tracer harnessTracer(SdkTracerProvider provider) { return provider.get("com.tianmu.agent.harness", "0.7.0"); }
}
