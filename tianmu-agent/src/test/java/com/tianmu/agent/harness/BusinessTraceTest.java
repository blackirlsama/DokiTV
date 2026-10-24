package com.tianmu.agent.harness;

import com.tianmu.agent.api.SseEventStreams;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTraceTest {
    private List<SpanData> spans(HarnessLifecycleTest.Fixture f, SpanType type) {
        return f.recording.spans.stream().filter(s -> type.value().equals(s.getAttributes().get(AttributeKey.stringKey("span.type")))).toList();
    }
    private SpanData one(HarnessLifecycleTest.Fixture f, SpanType type) {
        var spans = spans(f, type); assertEquals(1, spans.size(), type.name()); return spans.get(0);
    }
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test void decisionsUsageAndIdentityAreCorrelatedWithoutRawContent() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            RunState run = f.run();
            f.execute(run, context -> {
                TraceUsage.record("test-model", 100, 20);
                return context.observations().isEmpty() ? Decision.tool("list_skills", Map.of()) : Decision.clarify("SECRET_RESULT");
            });
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
            one(f, SpanType.RECEIVE_QUERY); one(f, SpanType.AGENT_START); one(f, SpanType.AGENT_END);
            var models = spans(f, SpanType.LLM_INFERENCE); assertEquals(2, models.size());
            assertEquals(List.of("CALL_TOOL", "CLARIFY"), models.stream().map(s -> s.getAttributes().get(AttributeKey.stringKey("model.decision"))).toList());
            assertEquals(true, one(f, SpanType.TOOL_CALL).getAttributes().get(AttributeKey.booleanKey("tool.success")));
            var finish = one(f, SpanType.FINISH);
            assertEquals("1.1", TraceEnvelope.from(finish, "test", "test").schemaVersion());
            assertEquals(200L, finish.getAttributes().get(AttributeKey.longKey("usage.input_tokens")));
            assertEquals(40L, finish.getAttributes().get(AttributeKey.longKey("usage.output_tokens")));
            assertEquals("CLARIFICATION", finish.getAttributes().get(AttributeKey.stringKey("finish.type")));
            assertEquals(run.sessionId.toString(), finish.getAttributes().get(AttributeKey.stringKey("session.id")));
            assertEquals("alice", finish.getAttributes().get(AttributeKey.stringKey("actor.id")));
            String exported = f.recording.spans.stream().map(s -> TraceEnvelope.from(s, "test", "test").toString()).toList().toString();
            assertFalse(exported.contains("SENSITIVE_PROMPT")); assertFalse(exported.contains("SECRET_RESULT"));
            assertFalse(exported.contains("llmThinkingContent"));
        }
    }
    @Test void faqShortCircuitRecordsRealRouteAndNeverPretendsToRunAnAgent() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService(
                (input, control) -> Optional.of(new FaqAnswer("intro", "v1", "curated answer")),
                new PreparationFlowTest.Model(), new PreparationPolicy(Duration.ofMillis(200), Duration.ofSeconds(2)), f.harness)) {
            RunState run = f.run();
            new RequestOrchestrator(prep, new AgentRuntime(c -> { fail("FAQ must not call the model"); return null; }, f.tools, f.harness), f.harness).execute(run);
            assertEquals("FAQ", one(f, SpanType.PRE_ROUTE).getAttributes().get(AttributeKey.stringKey("request.route")));
            assertEquals("intro", one(f, SpanType.FAQ_DIRECT).getAttributes().get(AttributeKey.stringKey("faq.id")));
            assertEquals("FAQ_ANSWER", one(f, SpanType.FINISH).getAttributes().get(AttributeKey.stringKey("finish.type")));
            assertTrue(spans(f, SpanType.AGENT_START).isEmpty()); assertTrue(spans(f, SpanType.LLM_INFERENCE).isEmpty());
        }
    }
    @Test void failuresHaveOneTerminalRecordAndSafeErrorCode() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            RunState run = f.run(); f.execute(run, c -> { throw new IllegalStateException("SECRET_EXCEPTION"); });
            assertEquals(Status.FAILED, run.snapshot().status());
            one(f, SpanType.ERROR); one(f, SpanType.FINISH); one(f, SpanType.AGENT_END);
            assertEquals("ERROR", one(f, SpanType.LLM_INFERENCE).getAttributes().get(AttributeKey.stringKey("model.decision")));
            assertFalse(f.recording.spans.toString().contains("SECRET_EXCEPTION"));
        }
    }
    @Test void queuedCancellationFinishesExactlyOnceWithoutAgentStart() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            RunState run = f.run(); f.harness.start(run); run.cancel(); run.cancel();
            one(f, SpanType.RECEIVE_QUERY); one(f, SpanType.FINISH);
            assertTrue(spans(f, SpanType.AGENT_START).isEmpty());
            assertEquals("USER_CANCELLED", one(f, SpanType.FINISH).getAttributes().get(AttributeKey.stringKey("finish.type")));
        }
    }
    @Test void sseMeasuresFirstEffectiveSendAndMarksReplayedSubscriptions() throws Exception {
        try (var f = new HarnessLifecycleTest.Fixture(); var streams = new SseEventStreams(f.tracer) {
            protected SseEmitter newEmitter() { return new SseEmitter() { public void send(SseEventBuilder event) {} }; }
        }) {
            RunState run = f.run(); f.harness.start(run); run.start(); streams.subscribe(run, 0);
            run.succeed(new Answer("private answer", List.of()));
            await(() -> spans(f, SpanType.TERMINAL_DELIVERY).size() == 1);
            var delivery = one(f, SpanType.TERMINAL_DELIVERY);
            assertEquals(false, delivery.getAttributes().get(AttributeKey.booleanKey("delivery.replay")));
            assertEquals(true, delivery.getAttributes().get(AttributeKey.booleanKey("delivery.success")));
            assertNotNull(delivery.getAttributes().get(AttributeKey.longKey("delivery.first_content_ms")));
            assertEquals(run.snapshot().traceId(), delivery.getAttributes().get(AttributeKey.stringKey("run.trace_id")));
            streams.subscribe(run, 0); await(() -> spans(f, SpanType.TERMINAL_DELIVERY).size() == 2);
            assertEquals(true, spans(f, SpanType.TERMINAL_DELIVERY).get(1).getAttributes().get(AttributeKey.booleanKey("delivery.replay")));
        }
    }
    @Test void progressAndCancellationAreNotMisreportedAsFirstAnswer() throws Exception {
        try (var f = new HarnessLifecycleTest.Fixture(); var streams = new SseEventStreams(f.tracer) {
            protected SseEmitter newEmitter() { return new SseEmitter() { public void send(SseEventBuilder event) {} }; }
        }) {
            RunState run = f.run(); f.harness.start(run); run.start(); streams.subscribe(run, 0); run.cancel();
            await(() -> !spans(f, SpanType.TERMINAL_DELIVERY).isEmpty());
            assertNull(one(f, SpanType.TERMINAL_DELIVERY).getAttributes().get(AttributeKey.longKey("delivery.first_content_ms")));
        }
    }
    @Test void exportPreservesBoundedTypedListsButRejectsArbitraryPayloads() {
        assertEquals(List.of("list_skills"), TraceAttributes.sanitize(Map.of("agent.available_tools", List.of("list_skills"))).get("agent.available_tools"));
        assertTrue(TraceAttributes.sanitize(Map.of("agent.available_tools", List.of(Map.of("secret", "raw")))).isEmpty());
        assertTrue(TraceAttributes.sanitize(Map.of("llmContent", "secret", "llmThinkingContent", "secret", "userQuery", "secret")).isEmpty());
    }
}
