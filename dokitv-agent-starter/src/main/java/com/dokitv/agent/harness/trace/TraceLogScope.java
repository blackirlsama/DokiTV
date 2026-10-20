package com.dokitv.agent.harness.trace;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;
import java.util.*;

/** Restore previous MDC values when pooled threads are reused or calls are nested. */
public final class TraceLogScope implements AutoCloseable {
    private final Map<String, String> previous;
    public TraceLogScope(Span span, String runId) {
        previous = MDC.getCopyOfContextMap();
        MDC.put("trace_id", span.getSpanContext().getTraceId());
        MDC.put("span_id", span.getSpanContext().getSpanId());
        if (runId != null) MDC.put("run_id", runId); else MDC.remove("run_id");
    }
    @Override public void close() { if (previous == null) MDC.clear(); else MDC.setContextMap(previous); }
}
