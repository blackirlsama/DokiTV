package com.tianmu.agent.harness.trace;

import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.*;

/** Versioned JSON transport schema; this is not an OTLP protobuf payload. */
public record TraceEnvelope(String schemaVersion, String eventId, String serviceName, String serviceVersion,
        String traceId, String spanId, String parentSpanId, String name, String kind,
        long startTimeUnixNano, long endTimeUnixNano, long durationNanos, String status,
        Map<String, Object> attributes) {
    public static TraceEnvelope from(SpanData span, String serviceName, String serviceVersion) {
        String name = span.getName();
        if (!name.matches("[a-zA-Z0-9_.-]{1,120}")) name = "agent.operation";
        return new TraceEnvelope("1.0", UUID.randomUUID().toString(), serviceName, serviceVersion,
                span.getTraceId(), span.getSpanId(), span.getParentSpanId(), name, span.getKind().name(),
                span.getStartEpochNanos(), span.getEndEpochNanos(),
                Math.max(0, span.getEndEpochNanos() - span.getStartEpochNanos()), span.getStatus().getStatusCode().name(),
                TraceAttributes.from(span.getAttributes()));
    }
}
