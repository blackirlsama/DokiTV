package com.tianmu.agent.harness;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PreparationFlowTest {
    static class Model implements PreparationModelPort {
        public Emotion emotion(Input i, CallControl c) { return Emotion.NEUTRAL; }
        Understanding accepted(Input i, String query) {
            return Understanding.proceed(TaskMeaning.defaults().resolve(i.query(), i.businessDate()).conditions(), query);
        }
        public Understanding understand(Input i, CallControl c) { return accepted(i, i.query()); }
    }
    PreparationService service(HarnessLifecycleTest.Fixture f, FaqPort faq, PreparationModelPort model) {
        return new PreparationService(faq, model, new PreparationPolicy(Duration.ofMillis(200), Duration.ofMillis(400)), f.harness);
    }
    FaqPort miss() { return (i, c) -> Optional.empty(); }
    ModelPort forbidden() { return c -> { fail("Harness model must not run"); return null; }; }
    void execute(HarnessLifecycleTest.Fixture f, PreparationService prep, RunState run, ModelPort model) {
        new RequestOrchestrator(prep, new AgentRuntime(model, f.tools, f.harness), f.harness).execute(run);
    }
    @Test void faqShortCircuitsAllModelsAndTools() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f,
                (i, c) -> Optional.of(new FaqAnswer("help", "1", "Approved help")), new Model() {
                    public Emotion emotion(Input i, CallControl c) { fail("Small model must not run"); return null; }
                })) {
            RunState run = f.run();
            execute(f, prep, run, forbidden());
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertEquals(StopReason.FAQ_ANSWER, run.snapshot().stopReason());
            assertEquals("help", run.snapshot().faqId());
            assertEquals(0, run.snapshot().modelCalls());
            assertEquals(0, run.snapshot().steps());
            assertEquals(0, f.queries.get());
            assertTrue(run.snapshot().answer().evidenceIds().isEmpty());
        }
    }
    @Test void understandingAndEmotionRunConcurrentlyAndCarryContext() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            void meet() {
                assertNotNull(org.slf4j.MDC.get("run_id"));
                try { barrier.await(300, TimeUnit.MILLISECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
            }
            public Emotion emotion(Input i, CallControl c) { meet(); return Emotion.NEGATIVE; }
            public Understanding understand(Input i, CallControl c) { meet(); return accepted(i, i.query() + " please"); }
        })) {
            RunState run = f.run();
            execute(f, prep, run, context -> {
                assertEquals(run.message + " please", context.message());
                assertEquals(run.message, context.preparation().originalQuery());
                assertTrue(context.preparation().emotion() == Emotion.NEGATIVE || context.preparation().skippedStages().contains("emotion"));
                assertEquals(run.owner, context.scope());
                return Decision.clarify("Next question");
            });
            assertEquals(Route.HARNESS, run.snapshot().route());
            assertTrue(run.snapshot().preparation().degradedStages().isEmpty());
            var spans = f.recording.spans.stream().filter(s -> s.getName().startsWith("agent.prepare.")).toList();
            assertEquals(4, spans.size());
            assertTrue(spans.stream().allMatch(s -> s.getTraceId().equals(run.snapshot().traceId())));
        }
    }
    @Test void clarificationStopsBeforeHarness() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Understanding understand(Input i, CallControl c) { return Understanding.clarify("Which date?"); }
        })) {
            RunState run = f.run(); execute(f, prep, run, forbidden());
            assertEquals(Route.CLARIFY, run.snapshot().route());
            assertEquals("Which date?", run.snapshot().clarification());
            assertEquals(0, run.snapshot().modelCalls());
        }
    }
    @Test void businessPrerequisitesOverrideFalseNegativeFromSmallModel() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService(miss(), new Model(),
                new PreparationPolicy(Duration.ofMillis(200), Duration.ofMillis(400)), f.harness, List.of(new MetricQueryReadiness()))) {
            RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001的弹幕数");
            execute(f, prep, run, forbidden());
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
            assertEquals(Route.CLARIFY, run.snapshot().route()); assertEquals(0, run.snapshot().totalModelCalls());
            assertTrue(run.snapshot().clarification().contains("日期"));
        }
    }
    @Test void emotionAndRewriteErrorsUseSafeDefaults() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Emotion emotion(Input i, CallControl c) { throw new IllegalStateException("down"); }
            public Understanding understand(Input i, CallControl c) { return accepted(i, "视频999999昨天的弹幕"); }
        })) {
            RunState run = f.run(); execute(f, prep, run, c -> {
                assertEquals(run.message, c.message()); return Decision.clarify("ok");
            });
            assertEquals(Emotion.UNKNOWN, run.snapshot().preparation().emotion());
            assertTrue(run.snapshot().preparation().degradedStages().contains("rewrite"));
            assertTrue(run.snapshot().preparation().degradedStages().contains("emotion") || run.snapshot().preparation().skippedStages().contains("emotion"));
        }
    }
    @Test void understandingOutageFailsClosed() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Understanding understand(Input i, CallControl c) { throw new IllegalStateException("down"); }
        })) {
            RunState run = f.run(); execute(f, prep, run, forbidden());
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
            assertEquals(Route.CLARIFY, run.snapshot().route());
            assertTrue(run.snapshot().preparation().degradedStages().contains("understanding"));
        }
    }
    @Test void slowSiblingDoesNotDiscardCompletedClarification() {
        CountDownLatch release = new CountDownLatch(1);
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Emotion emotion(Input i, CallControl c) {
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Emotion.POSITIVE;
            }
            public Understanding understand(Input i, CallControl c) { return Understanding.clarify("Which video?"); }
        })) {
            RunState run = f.run(); execute(f, prep, run, forbidden());
            assertEquals("Which video?", run.snapshot().clarification());
            assertTrue(run.snapshot().preparation().degradedStages().isEmpty());
            assertTrue(run.snapshot().preparation().skippedStages().contains("emotion"));
        } finally { release.countDown(); }
    }
    @Test void faqOutageFallsThroughButAuthorizationFailureDoesNot() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f,
                (i, c) -> { throw new IllegalStateException("down"); }, new Model())) {
            RunState run = f.run(); execute(f, prep, run, c -> Decision.clarify("ok"));
            assertEquals(Route.HARNESS, run.snapshot().route());
            assertTrue(run.snapshot().preparation().degradedStages().contains("faq"));
        }
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f,
                (i, c) -> { throw new AgentException("FORBIDDEN", "denied"); }, new Model())) {
            RunState run = f.run(); execute(f, prep, run, forbidden());
            assertEquals("FORBIDDEN", run.snapshot().errorCode());
        }
    }
    @Test void faqDeliveryStillRunsGate() {
        AgentHook gate = HarnessLifecycleTest.hook("deny", 0, AgentHook.Mode.GATE, Set.of(HookPoint.PRE_DELIVER),
                e -> { throw new HookRejectedException(); });
        try (var f = new HarnessLifecycleTest.Fixture(gate); var prep = service(f,
                (i, c) -> Optional.of(new FaqAnswer("help", "1", "help")), new Model())) {
            RunState run = f.run(); execute(f, prep, run, forbidden());
            assertEquals("HOOK_REJECTED", run.snapshot().errorCode());
            assertNull(run.snapshot().answer());
        }
    }
    @Test void cancelDuringParallelPreparationCannotDeliverLateResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        AtomicInteger cancelCallbacks = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            void block(CallControl c) {
                c.cancellation().onCancel(cancelCallbacks::incrementAndGet); entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            public Emotion emotion(Input i, CallControl c) { block(c); return Emotion.NEUTRAL; }
            public Understanding understand(Input i, CallControl c) { block(c); return accepted(i, i.query()); }
        })) {
            RunState run = f.run();
            Future<?> task = worker.submit(() -> execute(f, prep, run, forbidden()));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); run.cancel(); task.get(2, TimeUnit.SECONDS);
            assertEquals(Status.CANCELLED, run.snapshot().status());
            assertNull(run.snapshot().answer()); assertNull(run.snapshot().preparation());
            assertEquals(2, cancelCallbacks.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void modelConditionsCannotInventChangeOrOmitServerConditions() {
        for (Map<String, Object> conditions : List.of(
                Map.<String, Object>of("video_id", 1002L, "date", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1).toString(), "metric", "bullet_count"),
                Map.<String, Object>of("video_id", 1001L),
                Map.<String, Object>of("video_id", "1001", "date", "wrong", "metric", "bullet_count"),
                Map.<String, Object>of("user_id", "admin"))) {
            try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
                public Understanding understand(Input i, CallControl c) { return Understanding.proceed(conditions, i.query()); }
            })) {
                RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L, 1002L)), "查询视频1001昨天的弹幕数");
                execute(f, prep, run, forbidden());
                assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
                assertTrue(run.preparedRequest().degradedStages().contains("understanding"));
                assertNull(run.task()); assertEquals(0, f.queries.get());
            }
        }
    }
    @Test void integerJsonConditionsAreAcceptedButOnlyTheServerSpecificationIsFrozen() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Understanding understand(Input i, CallControl c) {
                return Understanding.proceed(Map.of("video_id", 1001, "date", i.businessDate().minusDays(1).toString(), "metric", "bullet_count"), i.query());
            }
        })) {
            RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001昨天的弹幕数");
            execute(f, prep, run, c -> Decision.clarify("ready"));
            assertEquals(Route.HARNESS, run.snapshot().route());
            assertEquals(1001L, run.task().specification().conditions().get("video_id"));
            assertEquals("user_input", run.task().specification().provenance().get("video_id"));
        }
    }
    @Test void missingRewriteFallsBackWithoutDiscardingValidUnderstanding() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Understanding understand(Input i, CallControl c) { return accepted(i, null); }
        })) {
            RunState run = f.run(); execute(f, prep, run, c -> Decision.clarify("ready"));
            assertEquals(Route.HARNESS, run.snapshot().route());
            assertEquals(run.message, run.task().query());
            assertTrue(run.preparedRequest().degradedStages().contains("rewrite"));
        }
    }
    @Test void unclassifiedTaskCannotAcquireModelInventedConditions() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = service(f, miss(), new Model() {
            public Understanding understand(Input i, CallControl c) {
                return Understanding.proceed(Map.of("video_id", 1001L), i.query());
            }
        })) {
            RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "分析一下");
            execute(f, prep, run, forbidden());
            assertEquals(Status.NEEDS_INPUT, run.snapshot().status()); assertNull(run.task());
        }
    }

}
