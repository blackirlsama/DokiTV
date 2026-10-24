package com.tianmu.agent.harness.trace;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.store.ObjectStore;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private, bounded snapshots. Exported span attributes contain references, never message bodies. */
@Component
public final class TraceDetails {
    public record Binding(TraceDetails store, UUID runId, java.util.concurrent.atomic.AtomicLong capturedBytes) {}
    public static final ContextKey<Binding> KEY = ContextKey.named("tianmu.trace.details");
    private static final Set<String> DIRECTIONS = Set.of("input", "output", "provider_output", "error");
    private final ObjectStore objects;
    private final ObjectMapper mapper;
    private final int maxBytes;
    private final boolean enabled;
    private long maxRunBytes = 8388608;
    @org.springframework.beans.factory.annotation.Autowired
    public TraceDetails(ObjectProvider<ObjectStore> objects, ObjectMapper mapper, TraceProperties properties,
            @Value("${harness.trace.details.max-bytes:131072}") int maxBytes,
            @Value("${harness.trace.details.enabled:true}") boolean enabled,
            @Value("${harness.trace.details.max-run-bytes:8388608}") long maxRunBytes) {
        this(objects.getIfAvailable(() -> new FileObjectStore(properties.directory().resolve("details"))), mapper, maxBytes, enabled);
        if (maxRunBytes < maxBytes || maxRunBytes > 67108864) throw new IllegalArgumentException("Invalid per-run trace detail limit");
        this.maxRunBytes = maxRunBytes;
    }
    public TraceDetails(ObjectStore objects, ObjectMapper mapper, int maxBytes, boolean enabled) {
        if (maxBytes < 4096 || maxBytes > 524288) throw new IllegalArgumentException("Trace detail limit must be 4 KiB to 512 KiB");
        this.objects = objects; this.mapper = mapper.copy().findAndRegisterModules(); this.maxBytes = maxBytes; this.enabled = enabled;
    }
    public Context bind(Context context, UUID runId) { return context.with(KEY, new Binding(this, runId, new java.util.concurrent.atomic.AtomicLong())); }
    public static void input(Object value) { capture("input", value, "LOGICAL_INPUT"); }
    public static void output(Object value) { capture("output", value, "RESULT"); }
    public static void capture(String direction, Object value, String kind) {
        Binding binding = Context.current().get(KEY);
        if (binding != null && Span.current().getSpanContext().isValid()) binding.store().write(binding, direction, value, kind);
    }
    private void write(Binding binding, String direction, Object value, String kind) {
        if (!DIRECTIONS.contains(direction)) return;
        Span span = Span.current(); String prefix = "detail." + direction;
        if (!enabled) { TraceAttributes.apply(span, Map.of(prefix + "_state", "DISABLED")); return; }
        try {
            boolean[] redacted = {false};
            JsonNode clean = clean(mapper.valueToTree(value), redacted);
            byte[] content = mapper.writeValueAsBytes(clean);
            int originalBytes = content.length;
            String state = content.length > maxBytes - 1024 ? "TRUNCATED" : "COMPLETE";
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("schemaVersion", "1.0"); envelope.put("kind", kind); envelope.put("state", state);
            envelope.put("redacted", redacted[0]); envelope.put("originalBytes", originalBytes);
            if (state.equals("TRUNCATED")) {
                // A JSON string preview is explicitly incomplete; never present a cut JSON object as complete.
                String preview = new String(content, StandardCharsets.UTF_8);
                int length = Math.min(preview.length(), (maxBytes - 1024) / 6);
                if (length > 0 && Character.isHighSurrogate(preview.charAt(length - 1))) length--;
                envelope.put("preview", preview.substring(0, length));
            } else envelope.set("value", clean);
            byte[] body = mapper.writeValueAsBytes(envelope);
            if (binding.capturedBytes().addAndGet(body.length) > maxRunBytes) {
                TraceAttributes.apply(span, Map.of(prefix + "_state", "LIMIT_REACHED", prefix + "_ref", "UNAVAILABLE"));
                return;
            }
            UUID runId = binding.runId();
            String sha = FileObjectStore.hash(body), spanId = span.getSpanContext().getSpanId();
            String key = key(runId, spanId, direction, sha);
            try { objects.put(key, body); }
            catch (RuntimeException e) { objects.get(key, body.length, sha); } // Same immutable snapshot may be captured twice.
            TraceAttributes.apply(span, Map.of(prefix + "_ref", spanId + "/" + direction + "/" + body.length + "/" + sha,
                    prefix + "_state", state, prefix + "_bytes", originalBytes, prefix + "_redacted", redacted[0]));
        } catch (RuntimeException | java.io.IOException e) {
            // Observability failure must not change the business result or leak the exception message.
            TraceAttributes.apply(span, Map.of(prefix + "_state", "CAPTURE_FAILED", prefix + "_ref", "UNAVAILABLE"));
        }
    }
    private JsonNode clean(JsonNode node, boolean[] redacted) {
        if (node == null) return NullNode.instance;
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey(), compact = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
                if (Set.of("password", "passwd", "apikey", "secret", "secretkey", "authorization", "authorizationref", "cookie", "credential", "accesstoken", "refreshtoken", "reasoningcontent").contains(compact)) {
                    result.put(name, "[REDACTED]"); redacted[0] = true;
                } else result.set(name, clean(entry.getValue(), redacted));
            });
            return result;
        }
        if (node.isArray()) { ArrayNode result = mapper.createArrayNode(); node.forEach(value -> result.add(clean(value, redacted))); return result; }
        if (node.isTextual()) {
            String original = node.textValue();
            String text = original.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]")
                    .replaceAll("(?i)(api[_-]?key|password|secret[_-]?key|authorization|access[_-]?token)([\\s\"':=]+)[^\\s\"',;}]+", "$1$2[REDACTED]")
                    .replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[email]")
                    .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[phone]");
            // JSON message strings often contain nested structured inputs (including scope credentials).
            String trimmed = original.strip();
            if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && original.length() < 1048576) {
                try { JsonNode parsed = mapper.readTree(original); return TextNode.valueOf(mapper.writeValueAsString(clean(parsed, redacted))); }
                catch (Exception ignored) { /* Ordinary prose is redacted by the rules above. */ }
            }
            if (!text.equals(original)) redacted[0] = true;
            return TextNode.valueOf(text);
        }
        return node;
    }
    public byte[] read(UUID runId, String spanId, String direction, long bytes, String sha) {
        if (!spanId.matches("[0-9a-f]{16}") || !DIRECTIONS.contains(direction) || !sha.matches("[0-9a-f]{64}") || bytes < 1 || bytes > 524288)
            throw new AgentException("INVALID_ARGUMENTS", "Invalid trace detail reference");
        return objects.get(key(runId, spanId, direction, sha), bytes, sha);
    }
    private static String key(UUID runId, String spanId, String direction, String sha) {
        return "td_" + FileObjectStore.hash((runId + "/" + spanId + "/" + direction + "/" + sha).getBytes(StandardCharsets.UTF_8)) + ".json";
    }
}
