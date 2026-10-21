package com.tianmu.agent.harness.trace;

import org.apache.kafka.clients.producer.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Explicit acknowledgement is awaited by the export worker, never by the model/tool worker. */
public final class KafkaTraceSink implements TraceSink {
    private final Producer<String, String> producer;
    private final String topic;
    public KafkaTraceSink(Producer<String, String> producer, String topic) {
        if (topic == null || !topic.matches("[A-Za-z0-9._-]{1,249}") || topic.equals(".") || topic.equals(".."))
            throw new IllegalArgumentException("Invalid trace topic");
        this.producer = producer;
        this.topic = topic;
    }
    @Override public void send(TraceEnvelope envelope, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, envelope.traceId(), json);
        record.headers().add("schema_version", envelope.schemaVersion().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        record.headers().add("event_id", envelope.eventId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        producer.send(record).get(8, TimeUnit.SECONDS);
    }
    @Override public void close() { producer.close(Duration.ofSeconds(2)); }
}
