package com.tianmu.agent.harness.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.api.trace.Span;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Request-scoped counters propagated with OTel Context, shared safely by parallel preparation calls. */
public final class TraceUsage {
    public static final ContextKey<TraceUsage> KEY = ContextKey.named("tianmu.trace.usage");
    private final LongAdder input = new LongAdder(), output = new LongAdder(), calls = new LongAdder();
    public static void record(String model, long inputTokens, long outputTokens) {
        long in = Math.max(0, inputTokens), out = Math.max(0, outputTokens);
        TraceAttributes.apply(Span.current(), Map.of("model.name", model,
                "model.input_tokens", in, "model.output_tokens", out));
        TraceUsage usage = Context.current().get(KEY);
        if (usage != null) { usage.input.add(in); usage.output.add(out); usage.calls.increment(); }
    }
    public Map<String, Object> totals() {
        return Map.of("usage.input_tokens", input.sum(), "usage.output_tokens", output.sum(),
                "usage.reported_calls", calls.sum(), "usage.scope", "RUN");
    }
}
