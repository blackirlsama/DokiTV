package com.dokitv.agent.harness;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.harness.trace.*;
import com.dokitv.agent.model.ModelPort;
import com.dokitv.agent.runtime.*;
import com.dokitv.agent.skill.SkillCatalog;
import com.dokitv.agent.tool.ToolDispatcher;
import com.dokitv.agent.tool.*;
import com.dokitv.agent.tool.builtin.*;
import com.dokitv.agent.query.BusinessQueryPort;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class HarnessLifecycleTest {
    static ToolDispatcher dispatcher(SkillCatalog skills, BusinessQueryPort queries, RuntimeHarness harness) {
        var registry = new ToolRegistry(List.of(new ListSkillsTool(), new ReadSkillTool(), new ReadResultTool(), new QueryVideoMetricsTool(queries)), new ToolCatalog(), skills);
        return new ToolDispatcher(skills, harness, registry);
    }
    static class Recording implements SpanExporter {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();
        public CompletableResultCode export(Collection<SpanData> items) { spans.addAll(items); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
    static AgentHook hook(String name, int order, AgentHook.Mode mode, Set<HookPoint> points, Consumer<HookEvent> consumer) {
        return new AgentHook() {
            public String name() { return name; }
            public int order() { return order; }
            public Mode mode() { return mode; }
            public Set<HookPoint> points() { return points; }
            public void onEvent(HookEvent event) { consumer.accept(event); }
        };
    }
    static class Fixture implements AutoCloseable {
        final Recording recording = new Recording();
        final SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build();
        final Tracer tracer = provider.get("test");
        final HookDispatcher dispatcher;
        final RuntimeHarness harness;
        final AtomicInteger queries = new AtomicInteger();
        final ToolDispatcher tools;
        Fixture(AgentHook... hooks) {
            this(List.of(), hooks);
        }
        Fixture(List<TransformHook<?>> transforms, AgentHook... hooks) {
            dispatcher = new HookDispatcher(List.of(hooks), tracer);
            harness = new RuntimeHarness(tracer, dispatcher, new TransformDispatcher(transforms, tracer,
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
            tools = dispatcher(new SkillCatalog(), (query, scope) -> {
                queries.incrementAndGet();
                return new MetricData(query.videoId(), query.date(), query.metric(), 128, "test-v1", "fixture",
                        Instant.now(), "Asia/Shanghai", true, true);
            }, harness);
        }
        RunState run() { return new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "SENSITIVE_PROMPT_abc123"); }
        void execute(RunState run, ModelPort model) { new AgentRuntime(model, tools, harness).execute(run); }
        public void close() { harness.close(); provider.close(); }
    }
    private AgentHook recording(List<HookEvent> events) {
        return hook("record", 0, AgentHook.Mode.OBSERVER, EnumSet.allOf(HookPoint.class), events::add);
    }
    private long count(List<HookEvent> events, HookPoint point) { return events.stream().filter(e -> e.point() == point).count(); }

    @Test void lifecycleAndParentTreeAreComplete() {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        try (Fixture fixture = new Fixture(recording(events))) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> Decision.clarify("Need a date"));
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
            assertEquals(1, count(events, HookPoint.REQUEST_START));
            assertEquals(1, count(events, HookPoint.REQUEST_END));
            assertEquals(1, count(events, HookPoint.PRE_MODEL));
            assertEquals(1, count(events, HookPoint.POST_DELIVER));
            assertTrue(events.stream().allMatch(event -> event.traceId().equals(run.snapshot().traceId())));
            SpanData root = fixture.recording.spans.stream().filter(s -> s.getName().equals("agent.run")).findFirst().orElseThrow();
            assertTrue(root.getEndEpochNanos() >= fixture.recording.spans.stream().mapToLong(SpanData::getEndEpochNanos).max().orElseThrow());
            Map<String, SpanData> byId = new HashMap<>();
            fixture.recording.spans.forEach(s -> byId.put(s.getSpanId(), s));
            fixture.recording.spans.stream().filter(s -> !s.getSpanId().equals(root.getSpanId()))
                    .forEach(s -> assertTrue(byId.containsKey(s.getParentSpanId()), s.getName()));
            assertFalse(Span.current().getSpanContext().isValid());
            assertNull(MDC.get("run_id"));
        }
    }
    @Test void observerExceptionIsIsolatedAndRegistryOrderIsDeterministic() {
        List<String> order = new ArrayList<>();
        try (Fixture fixture = new Fixture(
                hook("b", 1, AgentHook.Mode.OBSERVER, Set.of(HookPoint.PRE_MODEL), e -> order.add("b")),
                hook("a", 1, AgentHook.Mode.OBSERVER, Set.of(HookPoint.PRE_MODEL), e -> { order.add("a"); throw new IllegalStateException("SECRET"); }))) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> Decision.clarify("Date?"));
            assertEquals(List.of("a", "b"), order);
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
            assertEquals(1, fixture.dispatcher.observerFailureCount());
        }
    }
    @Test void gateStopsToolAndPostHookRecordsItWasNotExecuted() {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        try (Fixture fixture = new Fixture(recording(events),
                hook("deny-tool", -10, AgentHook.Mode.GATE, Set.of(HookPoint.PRE_TOOL), e -> { throw new HookRejectedException(); }))) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> Decision.tool("read_skill", Map.of("name", "video-metrics")));
            assertEquals("HOOK_REJECTED", run.snapshot().errorCode());
            assertTrue(run.execution().activeSkills().isEmpty());
            assertEquals(0, fixture.queries.get());
            assertEquals(1, count(events, HookPoint.ON_ERROR));
            assertEquals(1, count(events, HookPoint.REQUEST_END));
            HookEvent post = events.stream().filter(e -> e.point() == HookPoint.POST_TOOL).findFirst().orElseThrow();
            assertEquals(false, post.attributes().get("operation.executed"));
        }
    }
    @Test void skillGateStopsActivationAndAllowingHookCannotGrantBusinessAccess() {
        try (Fixture fixture = new Fixture(hook("deny-skill", 0, AgentHook.Mode.GATE, Set.of(HookPoint.PRE_SKILL),
                e -> { throw new HookRejectedException(); }))) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> Decision.tool("read_skill", Map.of("name", "video-metrics")));
            assertEquals("HOOK_REJECTED", run.snapshot().errorCode());
            assertTrue(run.execution().activeSkills().isEmpty());
        }
        try (Fixture fixture = new Fixture(hook("allow", 0, AgentHook.Mode.GATE, Set.of(HookPoint.PRE_TOOL), e -> {}))) {
            RunState run = fixture.run();
            run.execution().activateSkill("video-metrics");
            fixture.execute(run, ctx -> Decision.tool("query_video_metrics", Map.of("video_id", 1002,
                    "date", LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString(), "metric", "bullet_count")));
            assertEquals("FORBIDDEN", run.snapshot().errorCode());
            assertEquals(0, fixture.queries.get());
        }
    }
    @Test void deliveryGatePreventsFinalOutput() {
        try (Fixture fixture = new Fixture(hook("deny-deliver", 0, AgentHook.Mode.GATE, Set.of(HookPoint.PRE_DELIVER),
                e -> { throw new HookRejectedException(); }))) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> Decision.clarify("Date?"));
            assertEquals("HOOK_REJECTED", run.snapshot().errorCode());
            assertNull(run.snapshot().clarification());
        }
    }
    @Test void invalidRegistrationsFailAtStartup() {
        Recording recording = new Recording();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build()) {
            Tracer tracer = provider.get("test");
            AgentHook postGate = hook("post", 0, AgentHook.Mode.GATE, Set.of(HookPoint.POST_TOOL), e -> {});
            assertThrows(IllegalArgumentException.class, () -> new HookDispatcher(List.of(postGate), tracer));
            AgentHook duplicate = hook("same", 0, AgentHook.Mode.OBSERVER, Set.of(HookPoint.PRE_TOOL), e -> {});
            assertThrows(IllegalArgumentException.class, () -> new HookDispatcher(List.of(duplicate, duplicate), tracer));
        }
    }
    @Test void queuedCancellationAndDuplicateFinalizationAreExactlyOnce() {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        try (Fixture fixture = new Fixture(recording(events))) {
            RunState run = fixture.run();
            fixture.harness.start(run);
            run.cancel();
            run.cancel();
            fixture.execute(run, ctx -> { fail("Model must not run"); return null; });
            assertEquals(1, count(events, HookPoint.ON_CANCEL));
            assertEquals(1, count(events, HookPoint.REQUEST_END));
            assertEquals(1, fixture.recording.spans.stream().filter(s -> s.getName().equals("agent.run")).count());
        }
    }
    @Test void activeCancellationEndsRootAfterWorkerCleanup() throws Exception {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture(recording(events))) {
            RunState run = fixture.run();
            Future<?> future = executor.submit(() -> fixture.execute(run, ctx -> {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Decision.clarify("late");
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            run.cancel();
            assertEquals(1, count(events, HookPoint.ON_CANCEL));
            // Host cleanup may already finish after cancellation unblocks the bounded adapter wait.
            release.countDown();
            future.get(3, TimeUnit.SECONDS);
            assertEquals(1, count(events, HookPoint.REQUEST_END));
            assertEquals(Status.CANCELLED, run.snapshot().status());
            assertNull(run.snapshot().clarification());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void explicitAsyncContextPropagationRestoresPooledThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Fixture fixture = new Fixture()) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> {
                CompletableFuture.runAsync(fixture.harness.contextual(() -> {
                    assertEquals(run.id.toString(), MDC.get("run_id"));
                    fixture.tracer.spanBuilder("test.parallel").startSpan().end();
                }), executor).join();
                return Decision.clarify("Date?");
            });
            SpanData parallel = fixture.recording.spans.stream().filter(s -> s.getName().equals("test.parallel")).findFirst().orElseThrow();
            SpanData model = fixture.recording.spans.stream().filter(s -> s.getName().equals("agent.model")).findFirst().orElseThrow();
            assertEquals(model.getSpanId(), parallel.getParentSpanId());
            assertEquals(run.snapshot().traceId(), parallel.getTraceId());
            assertFalse(executor.submit(() -> Span.current().getSpanContext().isValid()).get());
            assertNull(executor.submit(() -> MDC.get("run_id")).get());
        } finally { executor.shutdownNow(); }
    }
    @Test void sensitivePromptAndExceptionTextAreNotExported() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RunState run = fixture.run();
            fixture.execute(run, ctx -> {
                Span.current().setAttribute("Authorization", "Bearer SECRET_TOKEN");
                Span.current().setAttribute("prompt", run.message);
                throw new IllegalStateException("SECRET_EXCEPTION_TEXT");
            });
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (SpanData span : fixture.recording.spans) {
                String json = mapper.writeValueAsString(TraceEnvelope.from(span, "test", "v1"));
                assertFalse(json.contains("SECRET_TOKEN"));
                assertFalse(json.contains("SENSITIVE_PROMPT"));
                assertFalse(json.contains("SECRET_EXCEPTION_TEXT"));
            }
        }
    }
}
