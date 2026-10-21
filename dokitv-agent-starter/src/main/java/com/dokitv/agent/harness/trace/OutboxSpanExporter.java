package com.dokitv.agent.harness.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synchronous durable enqueue, asynchronous destination delivery. Failures never relax runtime policy. */
public final class OutboxSpanExporter implements SpanExporter {
    private static final Logger log = LoggerFactory.getLogger(OutboxSpanExporter.class);
    private final TraceSpool spool;
    private final TraceSink sink;
    private final ObjectMapper mapper;
    private final TraceExportStats stats;
    private final ScheduledExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextWarning = new AtomicLong();
    private long nextAttemptMillis;
    private int consecutiveFailures;
    public record Stats(int pendingRecords, long pendingBytes, long enqueued, long delivered,
                        long enqueueFailures, long deliveryFailures, long quarantined) {}
    public Stats stats() { return new Stats(spool.pendingCount(), spool.usedBytes(), stats.enqueued.sum(),
            stats.delivered.sum(), stats.enqueueFailures.sum(), stats.deliveryFailures.sum(), stats.quarantined.sum()); }

    public OutboxSpanExporter(TraceSpool spool, TraceSink sink, ObjectMapper mapper, TraceExportStats stats) {
        this.spool = spool;
        this.sink = sink;
        this.mapper = mapper;
        this.stats = stats;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "harness-trace-export");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::drainSafely, 100, 200, TimeUnit.MILLISECONDS);
    }
    @Override public CompletableResultCode export(Collection<SpanData> spans) {
        if (closed.get()) return CompletableResultCode.ofFailure();
        boolean success = true;
        for (SpanData span : spans) {
            try {
                TraceEnvelope event = TraceEnvelope.from(span, "dokitv-operations-agent", "0.7.0");
                spool.append(mapper.writeValueAsString(event));
                stats.enqueued.increment();
            } catch (Exception e) {
                success = false;
                stats.enqueueFailures.increment();
                warn("enqueue_failed", e);
            }
        }
        return success ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
    }
    private void drainSafely() {
        try { drain(); }
        catch (Exception e) { stats.deliveryFailures.increment(); warn("export_worker_failed", e); }
    }
    private void drain() throws Exception {
        if (System.currentTimeMillis() < nextAttemptMillis) return;
        for (int batch = 0; batch < 100; batch++) {
            Path path = spool.oldest();
            if (path == null) { consecutiveFailures = 0; return; }
            String json;
            TraceEnvelope event;
            try {
                json = spool.read(path);
                event = mapper.readValue(json, TraceEnvelope.class);
                if (event == null || !"1.0".equals(event.schemaVersion()) || event.eventId() == null
                        || event.traceId() == null || !event.traceId().matches("[0-9a-f]{32}"))
                    throw new IllegalArgumentException("Invalid trace envelope");
            } catch (Exception e) {
                spool.quarantine(path);
                stats.quarantined.increment();
                warn("record_quarantined", e);
                continue;
            }
            try {
                sink.send(event, json);
                // If the process dies after destination ACK but before deletion, replay duplicates event_id.
                spool.acknowledge(path);
                stats.delivered.increment();
                consecutiveFailures = 0;
            } catch (Exception e) {
                stats.deliveryFailures.increment();
                long base = Math.min(30000, 250L << Math.min(consecutiveFailures++, 7));
                nextAttemptMillis = System.currentTimeMillis() + base + ThreadLocalRandom.current().nextLong(100);
                warn("delivery_failed_retained", e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return;
            }
        }
    }
    private void warn(String code, Exception exception) {
        long now = System.currentTimeMillis();
        long expected = nextWarning.get();
        if (now >= expected && nextWarning.compareAndSet(expected, now + 10000))
            log.warn("trace_export={} type={} pending={}", code, exception.getClass().getSimpleName(), spool.pendingCount());
    }
    @Override public CompletableResultCode flush() {
        CompletableResultCode result = new CompletableResultCode();
        if (closed.get()) return result.fail();
        try {
            worker.execute(() -> {
                drainSafely();
                if (spool.pendingCount() == 0) result.succeed(); else result.fail();
            });
        } catch (RejectedExecutionException e) { result.fail(); }
        return result;
    }
    @Override public CompletableResultCode shutdown() {
        if (!closed.compareAndSet(false, true)) return CompletableResultCode.ofSuccess();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                if (!worker.awaitTermination(2, TimeUnit.SECONDS)) return CompletableResultCode.ofFailure();
            }
            try { sink.close(); } finally { spool.close(); }
            return spool.pendingCount() == 0 ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            warn("shutdown_incomplete", e);
            return CompletableResultCode.ofFailure();
        }
    }
}
