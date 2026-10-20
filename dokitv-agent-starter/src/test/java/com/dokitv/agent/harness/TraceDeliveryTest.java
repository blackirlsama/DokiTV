package com.dokitv.agent.harness;

import com.dokitv.agent.harness.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class TraceDeliveryTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Condition not reached before timeout");
    }
    TraceEnvelope envelope(String id) {
        return new TraceEnvelope("1.0", id, "test", "v1", "123456789012345678901234567890ab", "1234567890123456",
                "0000000000000000", "agent.run", "INTERNAL", 1, 2, 1, "UNSET", Map.of("run.id", "test"));
    }
    @Test void durableSpoolSurvivesRestartAndRejectsAnotherOwner() throws Exception {
        try (TraceSpool spool = new TraceSpool(directory, 3, 8192, 2048)) {
            spool.append("first");
            assertThrows(Exception.class, () -> new TraceSpool(directory, 3, 8192, 2048));
        }
        try (TraceSpool reopened = new TraceSpool(directory, 3, 8192, 2048)) {
            assertEquals(1, reopened.pendingCount());
            Path record = reopened.oldest();
            assertEquals("first", reopened.read(record));
            reopened.acknowledge(record);
            assertEquals(0, reopened.pendingCount());
        }
    }
    @Test void capacityAndRecordSizeAreBoundedWithoutOverwritingOldRecords() throws Exception {
        try (TraceSpool spool = new TraceSpool(directory, 1, 2048, 1024)) {
            assertThrows(IOException.class, () -> spool.append("x".repeat(1025)));
            spool.append("first");
            assertThrows(IOException.class, () -> spool.append("second"));
            assertEquals("first", spool.read(spool.oldest()));
        }
    }
    @Test void failedSinkRetainsAndRestartReplaysStableEventId() throws Exception {
        TraceExportStats stats = new TraceExportStats();
        TraceSpool firstSpool = new TraceSpool(directory, 10, 65536, 4096);
        OutboxSpanExporter first = new OutboxSpanExporter(firstSpool, (event, json) -> { throw new IOException("broker down"); }, mapper, stats);
        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(first)).build()) {
            provider.get("test").spanBuilder("test.delivery").startSpan().end();
            await(() -> stats.deliveryFailures.sum() > 0);
            assertEquals(1, firstSpool.pendingCount());
        }
        List<TraceEnvelope> received = new CopyOnWriteArrayList<>();
        TraceSpool secondSpool = new TraceSpool(directory, 10, 65536, 4096);
        String original = secondSpool.read(secondSpool.oldest());
        String eventId = mapper.readValue(original, TraceEnvelope.class).eventId();
        OutboxSpanExporter second = new OutboxSpanExporter(secondSpool, (event, json) -> received.add(event), mapper, new TraceExportStats());
        try {
            await(() -> secondSpool.pendingCount() == 0);
            assertEquals(eventId, received.get(0).eventId());
        } finally { second.shutdown().join(3, TimeUnit.SECONDS); }
    }
    @Test void corruptRecordsAreQuarantinedAndDoNotBlockValidRecords() throws Exception {
        TraceSpool spool = new TraceSpool(directory, 10, 65536, 4096);
        spool.append("not-json");
        spool.append(mapper.writeValueAsString(envelope("valid")));
        List<String> received = new CopyOnWriteArrayList<>();
        TraceExportStats stats = new TraceExportStats();
        OutboxSpanExporter exporter = new OutboxSpanExporter(spool, (event, json) -> received.add(event.eventId()), mapper, stats);
        try {
            await(() -> received.size() == 1 && spool.pendingCount() == 0);
            assertEquals(List.of("valid"), received);
            assertEquals(1, stats.quarantined.sum());
            try (var paths = Files.list(directory.resolve("pending"))) {
                assertEquals(1, paths.filter(p -> p.toString().endsWith(".corrupt")).count());
            }
        } finally { exporter.shutdown().join(3, TimeUnit.SECONDS); }
    }
    @Test void spoolFailureIsObservableWithoutBreakingAgentCall() throws Exception {
        TraceSpool spool = new TraceSpool(directory, 1, 4096, 2048);
        spool.append("preserved-malformed-record");
        TraceExportStats stats = new TraceExportStats();
        OutboxSpanExporter exporter = new OutboxSpanExporter(spool, (event, json) -> {}, mapper, stats);
        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            assertDoesNotThrow(() -> provider.get("test").spanBuilder("test.capacity").startSpan().end());
            assertEquals(1, stats.enqueueFailures.sum());
        }
    }
    @Test void kafkaRecordUsesTraceKeyAndVersionedStableEventHeaders() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        try (KafkaTraceSink sink = new KafkaTraceSink(producer, "agent.trace.v1")) {
            TraceEnvelope event = envelope("event-123");
            String json = mapper.writeValueAsString(event);
            sink.send(event, json);
            ProducerRecord<String, String> sent = producer.history().get(0);
            assertEquals("agent.trace.v1", sent.topic());
            assertEquals(event.traceId(), sent.key());
            assertEquals(json, sent.value());
            assertEquals("event-123", new String(sent.headers().lastHeader("event_id").value(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test void kafkaFailureDoesNotAcknowledgeSpoolRecord() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        TraceSpool spool = new TraceSpool(directory, 10, 65536, 4096);
        spool.append(mapper.writeValueAsString(envelope("retry-me")));
        TraceExportStats stats = new TraceExportStats();
        OutboxSpanExporter exporter = new OutboxSpanExporter(spool, new KafkaTraceSink(producer, "agent.trace.v1"), mapper, stats);
        try {
            await(() -> !producer.history().isEmpty());
            assertEquals(1, spool.pendingCount());
            producer.errorNext(new org.apache.kafka.common.errors.TimeoutException("broker down"));
            await(() -> stats.deliveryFailures.sum() > 0);
            assertEquals(1, spool.pendingCount());
            producer.completeNext();
            await(() -> producer.history().size() >= 2);
            producer.completeNext();
            await(() -> spool.pendingCount() == 0);
            assertEquals(1, stats.delivered.sum());
        } finally { exporter.shutdown().join(10, TimeUnit.SECONDS); }
    }
    @Test void localArchiveRotatesWithBoundedBackups() throws Exception {
        JsonlTraceSink sink = new JsonlTraceSink(directory, 1024, 2);
        for (int i = 0; i < 5; i++) sink.send(envelope("id-" + i), "x".repeat(700));
        try (var files = Files.list(directory)) { assertEquals(3, files.count()); }
    }
}
