package com.tianmu.agent.harness.trace;

import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.*;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Component
public class HarnessHttpTraceFilter extends OncePerRequestFilter {
    private final Tracer tracer;
    public HarnessHttpTraceFilter(Tracer tracer) { this.tracer = tracer; }
    private final TextMapGetter<HttpServletRequest> getter = new TextMapGetter<>() {
        public Iterable<String> keys(HttpServletRequest request) { return List.of("traceparent", "tracestate"); }
        public String get(HttpServletRequest request, String key) { return request == null ? null : request.getHeader(key); }
    };
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/agent/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), request, getter);
        Span span = tracer.spanBuilder("http.agent").setSpanKind(SpanKind.SERVER).setParent(parent).startSpan();
        TraceNode.begin(span, "http.agent");
        TraceAttributes.apply(span, Map.of("http.method", request.getMethod(), "http.route", "/api/agent/runs"));
        response.setHeader("X-Trace-ID", span.getSpanContext().getTraceId());
        try (Scope scope = span.makeCurrent(); TraceLogScope mdc = new TraceLogScope(span, null)) {
            chain.doFilter(request, response);
            span.setAttribute("http.status_code", response.getStatus());
            if (response.getStatus() >= 400) span.setStatus(StatusCode.ERROR);
            TraceAttributes.apply(span, Map.of("node.status", response.getStatus() >= 400 ? "FAILED" : "SUCCEEDED"));
        } catch (ServletException | IOException | RuntimeException e) {
            span.setStatus(StatusCode.ERROR);
            TraceAttributes.apply(span, Map.of("node.status", "FAILED"));
            throw e;
        } finally { span.end(); }
    }
}
