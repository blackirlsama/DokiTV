package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceDetailsTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Test void privateSnapshotsRedactNestedCredentialsAndMarkTruncationWithoutExportingBodies() throws Exception {
        var recording = new HarnessLifecycleTest.Recording();
        var details = new TraceDetails(new FileObjectStore(directory), json, 4096, true);
        UUID run = UUID.randomUUID();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()) {
            var span = provider.get("test").spanBuilder("agent.model").startSpan();
            try (var scope = details.bind(Context.root().with(span), run).makeCurrent()) {
                TraceNode.begin(span, "agent.model");
                TraceDetails.input(Map.of("apiKey", "secret-key", "messages", List.of(Map.of("content", "{\"scope\":{\"authorizationRef\":\"scope-secret\"},\"email\":\"person@example.com\",\"phone\":\"13800138000\"}"))));
                TraceDetails.output(Map.of("large", "文本".repeat(10000)));
                TraceNode.success(span);
            } finally { span.end(); }
            var data = recording.spans.get(0);
            String inputRef = data.getAttributes().get(AttributeKey.stringKey("detail.input_ref"));
            String[] parts = inputRef.split("/");
            String snapshot = new String(details.read(run, parts[0], parts[1], Long.parseLong(parts[2]), parts[3]), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(snapshot.contains("secret-key")); assertFalse(snapshot.contains("scope-secret"));
            assertFalse(snapshot.contains("person@example.com")); assertFalse(snapshot.contains("13800138000"));
            assertTrue(json.readTree(snapshot).path("redacted").asBoolean());
            assertEquals("TRUNCATED", data.getAttributes().get(AttributeKey.stringKey("detail.output_state")));
            assertThrows(AgentException.class, () -> details.read(UUID.randomUUID(), parts[0], parts[1], Long.parseLong(parts[2]), parts[3]));
            assertFalse(TraceEnvelope.from(data, "test", "v1").toString().contains("authorizationRef"));
        }
    }
    @Test void unknownUsageIsAbsentAndKnownOptionalUsageAndTimeoutStateAreExplicit() {
        var recording = new HarnessLifecycleTest.Recording();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()) {
            var span = provider.get("test").spanBuilder("agent.model").startSpan();
            TraceUsage usage = new TraceUsage();
            assertFalse(usage.totals().containsKey("usage.input_tokens"));
            try (var scope = Context.root().with(span).with(TraceUsage.KEY, usage).makeCurrent()) {
                TraceNode.begin(span, "agent.model");
                TraceUsage.record("mock", 12L, 4L, 8L, 4L, 2L);
                TraceNode.failure(span, new AgentException("MODEL_CALL_TIMEOUT", "Call exceeded time budget"));
            } finally { span.end(); }
            var attributes = recording.spans.get(0).getAttributes();
            assertEquals("TIMED_OUT", attributes.get(AttributeKey.stringKey("node.status")));
            assertEquals(16L, attributes.get(AttributeKey.longKey("model.total_tokens")));
            assertEquals(8L, usage.totals().get("usage.cache_hit_tokens"));
            assertEquals(2L, usage.totals().get("usage.reasoning_tokens"));
        }
    }
}
