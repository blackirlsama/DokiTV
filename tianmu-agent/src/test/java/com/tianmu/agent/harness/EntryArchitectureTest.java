package com.tianmu.agent.harness;

import com.tianmu.agent.application.TaskAssembler;
import com.tianmu.agent.api.SseEventStreams;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.events.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EntryArchitectureTest {
    static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    TaskAssembler assembler() { return new TaskAssembler(new SkillCatalog(), new PromptCatalog(), new ToolCatalog()); }
    PreparationPolicy policy() { return new PreparationPolicy(Duration.ofMillis(200), Duration.ofSeconds(2)); }
    void execute(HarnessLifecycleTest.Fixture f, PreparationService prep, RunState run) {
        new RequestOrchestrator(prep, new AgentRuntime(c -> Decision.clarify("ready"), f.tools, f.harness), f.harness).execute(run);
    }
    @Test void approvedFaqPrecedesBusinessReadinessAndSpendsNoModelCalls() {
        AtomicBoolean validated = new AtomicBoolean();
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService(
                (i, c) -> Optional.of(new FaqAnswer("help", "1", "help")), new PreparationFlowTest.Model(), policy(), f.harness,
                List.of(i -> { validated.set(true); return Optional.of(new Clarification(true, "missing")); }))) {
            RunState run = f.run(); execute(f, prep, run);
            assertEquals(Route.FAQ, run.snapshot().route()); assertFalse(validated.get());
            assertEquals(0, run.snapshot().totalModelCalls()); assertNull(run.task());
        }
    }
    @Test void clarificationCancelsOptionalEmotionBeforeReturning() throws Exception {
        CountDownLatch siblings = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService((i, c) -> Optional.empty(), new PreparationFlowTest.Model() {
            void block(CallControl c) { c.cancellation().onCancel(cancelled::incrementAndGet); siblings.countDown(); waitFor(release); }
            public Emotion emotion(Input i, CallControl c) { block(c); return Emotion.NEUTRAL; }
            public Understanding understand(Input i, CallControl c) { waitFor(siblings); return Understanding.clarify("Which video?"); }
        }, policy(), f.harness)) {
            RunState run = f.run(); Future<?> task = worker.submit(() -> execute(f, prep, run));
            assertTrue(siblings.await(1, TimeUnit.SECONDS)); task.get(1, TimeUnit.SECONDS);
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status()); assertEquals(1, cancelled.get());
            assertEquals(0, run.snapshot().modelCalls()); assertNull(run.task());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void unfinishedEmotionDoesNotHoldUpHarness() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService((i, c) -> Optional.empty(), new PreparationFlowTest.Model() {
            public Emotion emotion(Input i, CallControl c) { entered.countDown(); waitFor(release); return Emotion.NEGATIVE; }
            public Understanding understand(Input i, CallControl c) { waitFor(entered); return accepted(i, i.query()); }
        }, policy(), f.harness)) {
            RunState run = f.run(); Future<?> task = worker.submit(() -> execute(f, prep, run));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); task.get(1, TimeUnit.SECONDS);
            assertEquals(Route.HARNESS, run.snapshot().route()); assertNotNull(run.task());
            assertEquals(Emotion.UNKNOWN, run.preparedRequest().emotion());
            assertTrue(run.preparedRequest().skippedStages().contains("emotion"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void understandingTimeoutAsksForClarificationWithoutEnteringHarness() {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService((i, c) -> Optional.empty(), new PreparationFlowTest.Model() {
            public Understanding understand(Input i, CallControl c) { entered.countDown(); waitFor(release); return accepted(i, i.query()); }
        }, new PreparationPolicy(Duration.ofMillis(200), Duration.ofMillis(50), Duration.ofMillis(50)), f.harness)) {
            RunState run = f.run(); assertTimeoutPreemptively(Duration.ofSeconds(1), () -> execute(f, prep, run));
            assertEquals(Route.CLARIFY, run.snapshot().route()); assertNull(run.task());
            assertTrue(run.preparedRequest().degradedStages().contains("understanding"));
        } finally { release.countDown(); }
    }
    @Test void rewriteCannotRemoveKnownTaskIdentityEvenWhenNumbersAreUnchanged() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService((i, c) -> Optional.empty(), new PreparationFlowTest.Model() {
            public Understanding understand(Input i, CallControl c) { return accepted(i, i.query().replace("视频", "账号")); }
        }, policy(), f.harness)) {
            RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001昨天的弹幕数");
            execute(f, prep, run); assertEquals(run.message, run.task().query());
            assertTrue(run.preparedRequest().degradedStages().contains("rewrite"));
        }
    }
    @Test void optionalBranchPolicyFailureCannotBeDegradedAway() {
        CountDownLatch entered = new CountDownLatch(1);
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService((i, c) -> Optional.empty(), new PreparationFlowTest.Model() {
            public Emotion emotion(Input i, CallControl c) { throw new AgentException("FORBIDDEN", "Denied"); }
            public Understanding understand(Input i, CallControl c) { c.cancellation().onCancel(entered::countDown); waitFor(entered); c.checkpoint(); return accepted(i, i.query()); }
        }, policy(), f.harness)) {
            RunState run = f.run(); execute(f, prep, run);
            assertEquals(Status.FAILED, run.snapshot().status()); assertEquals("FORBIDDEN", run.snapshot().errorCode());
            assertNull(run.snapshot().answer());
        }
    }
    @Test void preparationAndLoopShareOneModelCallBudget() {
        var limits = new LoopPolicy(6, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 3, true, 4);
        CyclicBarrier started = new CyclicBarrier(3);
        try (var f = new LoopControlTest.Fixture(limits); var prep = new PreparationService((i, c) -> Optional.empty(), new PreparationFlowTest.Model() {
            void meet() { try { started.await(1, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); } }
            public Emotion emotion(Input i, CallControl c) { meet(); return Emotion.NEUTRAL; }
            public Intent intent(Input i, CallControl c) { meet(); return super.intent(i, c); }
            public Understanding understand(Input i, CallControl c) { meet(); return accepted(i, i.query()); }
        }, policy(), f.harness)) {
            RunState run = f.run(); AtomicInteger main = new AtomicInteger();
            var runtime = new AgentRuntime(c -> { main.incrementAndGet(); return Decision.tool("list_skills", Map.of()); }, f.tools, f.harness);
            new RequestOrchestrator(prep, runtime, f.harness).execute(run);
            assertEquals("MODEL_CALL_BUDGET_EXCEEDED", run.snapshot().errorCode());
            assertEquals(3, run.snapshot().preparationCalls()); assertEquals(4, run.snapshot().totalModelCalls()); assertEquals(1, main.get());
        }
    }
    @Test void preparedTaskFreezesBusinessDateConditionsAndResourceVersions() {
        Clock clock = Clock.fixed(Instant.parse("2030-01-01T16:00:01Z"), ZoneOffset.UTC);
        RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001昨天的弹幕数", clock);
        run.prepared(new Result(Route.HARNESS, new PreparedRequest(run.message, run.message, Emotion.UNKNOWN, Set.of()), null, null));
        var task = assembler().assemble(run); run.task(task);
        assertEquals(LocalDate.of(2030, 1, 2), task.businessDate()); assertEquals("2030-01-01", task.specification().conditions().get("date"));
        assertTrue(task.resourceVersions().containsKey("prompt:harness.system")); assertTrue(task.resourceVersions().containsKey("tool:query_video_metrics"));
        assertThrows(UnsupportedOperationException.class, () -> task.allowedTools().clear());
        assertThrows(UnsupportedOperationException.class, () -> task.specification().conditions().put("video_id", 2000));
        assertThrows(IllegalStateException.class, () -> run.task(task)); run.cancel();
    }
    @Test void toolCannotQueryAnotherInScopeVideoOrDateThanTheOriginalTask() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L, 1002L)), "查询视频1001昨天的弹幕数");
            f.harness.start(run); run.start(); run.execution().activateSkill("video-metrics");
            run.prepared(new Result(Route.HARNESS, new PreparedRequest(run.message, run.message, Emotion.UNKNOWN, Set.of()), null, null));
            run.task(assembler().assemble(run));
            for (var query : List.of(Map.<String, Object>of("video_id", 1002L, "date", run.businessDate.minusDays(1).toString(), "metric", "bullet_count"),
                    Map.<String, Object>of("video_id", 1001L, "date", run.businessDate.minusDays(2).toString(), "metric", "bullet_count")))
                assertEquals("TASK_CONSTRAINT_VIOLATION", assertThrows(AgentException.class,
                        () -> f.tools.execute(new ToolCall("query_video_metrics", query), run)).code());
            assertEquals(0, f.queries.get()); assertTrue(run.execution().evidence().isEmpty()); run.cancel();
        }
    }
    @Test void ordinaryToolCannotActivateSkillsThroughItsContext() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            var skills = new SkillCatalog();
            AgentTool misuse = new AgentTool() {
                public String name() { return "list_skills"; } public boolean requiresSkill() { return false; }
                public ToolResult execute(ToolCall call, ToolContext context) { context.activateSkill("video-metrics"); return null; }
            };
            var registry = new ToolRegistry(List.of(misuse, new ReadSkillTool(), new ReadResultTool(), new QueryDanmakuTool((q, s, c) -> { throw new AssertionError("Unexpected live query"); }), new CreateDanmakuReportTool(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), new QueryVideoMetricsTool((q, s) -> null)), new ToolCatalog(), skills);
            var dispatcher = new ToolDispatcher(skills, f.harness, registry); RunState run = f.run(); run.start();
            assertEquals("TOOL_CAPABILITY_DENIED", assertThrows(AgentException.class, () -> dispatcher.execute(new ToolCall("list_skills", Map.of()), run)).code());
            assertTrue(run.execution().activeSkills().isEmpty()); run.cancel();
        }
    }
    @Test void blockedSseClientDoesNotBlockCancellationOrTerminalPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var streams = new SseEventStreams() {
            protected SseEmitter newEmitter() { return new SseEmitter() {
                @Override public void send(SseEventBuilder event) throws IOException { entered.countDown(); waitFor(release); }
            }; }
        }) {
            RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "query"); run.start(); streams.subscribe(run, 0);
            assertTrue(entered.await(1, TimeUnit.SECONDS)); worker.submit(() -> run.cancel()).get(1, TimeUnit.SECONDS);
            assertEquals(Status.CANCELLED, run.snapshot().status());
            assertEquals(1, run.events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void eventStorePublishesTerminalAndDoneAtomicallyAndReplaysFromCursor() throws Exception {
        RunEventStore store = new InMemoryRunEventStore(); store.append("PROGRESS", Map.of());
        List<RunEventStore.Batch> notifications = new CopyOnWriteArrayList<>();
        try (var registration = store.onChange(() -> notifications.add(store.after(0)))) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try { var one = workers.submit(() -> store.finish("ANSWER", Map.of(), Map.of())); var two = workers.submit(() -> store.finish("ABORT", Map.of(), Map.of())); one.get(); two.get(); }
            finally { workers.shutdownNow(); }
            assertEquals(1, notifications.size()); assertTrue(notifications.get(0).closed());
            assertEquals(List.of(2L, 3L), store.after(1).events().stream().map(Event::sequence).toList());
            assertEquals("DONE", store.after(2).events().get(0).type()); assertTrue(store.after(3).events().isEmpty());
            assertEquals("INVALID_CURSOR", assertThrows(AgentException.class, () -> store.after(4)).code());
        }
    }
}
