package com.dokitv.agent.harness;

import com.dokitv.agent.core.StopReason;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.model.ModelPort;
import com.dokitv.agent.query.BusinessQueryPort;
import com.dokitv.agent.runtime.*;
import com.dokitv.agent.runtime.control.*;
import com.dokitv.agent.skill.SkillCatalog;
import com.dokitv.agent.tool.ToolDispatcher;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LoopControlTest {
    static LoopPolicy policy(int turns) {
        return new LoopPolicy(turns, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100), 3, true);
    }
    static class Fixture implements AutoCloseable {
        final SdkTracerProvider provider = SdkTracerProvider.builder().build();
        final LoopControl control;
        final RuntimeHarness harness;
        final ToolDispatcher tools;
        Fixture(LoopPolicy policy, BusinessQueryPort query) {
            control = new LoopControl(policy);
            var tracer = provider.get("loop-test");
            harness = new RuntimeHarness(tracer, new HookDispatcher(List.of(), tracer),
                    new TransformDispatcher(List.of(), tracer, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), control);
            tools = HarnessLifecycleTest.dispatcher(new SkillCatalog(), query, harness);
        }
        Fixture(LoopPolicy policy) { this(policy, (q, s) -> new MetricData(q.videoId(), q.date(), q.metric(), 128,
                "v1", "fixture", Instant.now(), "Asia/Shanghai", true, true)); }
        RunState run() {
            RunState r = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001昨天的弹幕数");
            r.execution().activateSkill("video-metrics"); return r;
        }
        void execute(RunState run, ModelPort model) { new AgentRuntime(model, tools, harness).execute(run); }
        public void close() { control.close(); harness.close(); provider.close(); }
    }
    static Decision query() { return Decision.tool("query_video_metrics", Map.of("video_id", 1001L,
            "date", LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString(), "metric", "bullet_count")); }
    @Test void turnLimitAllowsExactlyOneFinalModelCallWithNoTools() {
        try (Fixture f = new Fixture(policy(2))) {
            RunState run = f.run();
            f.execute(run, c -> {
                if (c.finalizing()) {
                    assertTrue(c.availableTools().isEmpty()); assertEquals(0, c.remainingSteps());
                    assertFalse(c.systemDirective().isBlank());
                    return Decision.answer("128", List.of(run.execution().evidence().snapshot().keySet().iterator().next()));
                }
                return c.observations().isEmpty() ? query() : Decision.tool("list_skills", Map.of());
            });
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertEquals(StopReason.FINALIZED_ANSWER, run.snapshot().stopReason());
            assertEquals(2, run.snapshot().steps()); assertEquals(3, run.snapshot().modelCalls());
        }
    }
    @Test void finalizerCannotExecuteAnotherToolAndFallsBackToEvidence() {
        try (Fixture f = new Fixture(policy(1))) {
            RunState run = f.run(); f.execute(run, c -> c.finalizing() ? Decision.tool("list_skills", Map.of()) : query());
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertEquals(1, run.execution().observations().size());
            assertTrue(run.snapshot().answer().text().contains("128"));
            assertEquals(2, run.snapshot().modelCalls());
        }
    }
    @Test void noEvidenceDoesNotTriggerFinalization() {
        try (Fixture f = new Fixture(policy(1))) {
            RunState run = f.run(); f.execute(run, c -> Decision.tool("list_skills", Map.of()));
            assertEquals("STEP_LIMIT", run.snapshot().errorCode()); assertEquals(1, run.snapshot().modelCalls());
        }
    }
    @Test void invalidFinalCitationsCannotUseFallbackToBypassValidation() {
        try (Fixture f = new Fixture(policy(1))) {
            RunState run = f.run(); f.execute(run, c -> c.finalizing() ? Decision.answer("made up", List.of("forged")) : query());
            assertEquals("INVALID_EVIDENCE", run.snapshot().errorCode()); assertNull(run.snapshot().answer());
        }
    }
    @Test void threeExplicitTransientFailuresStopWithoutFourthToolAttempt() {
        AtomicInteger count = new AtomicInteger();
        try (Fixture f = new Fixture(policy(6), (q, s) -> {
            count.incrementAndGet(); throw new TransientToolException();
        })) {
            RunState run = f.run(); f.execute(run, c -> query());
            assertEquals("TOOL_FAILURE_LIMIT", run.snapshot().errorCode()); assertEquals(3, count.get());
            assertEquals(2, run.execution().observations().size()); assertTrue(run.execution().evidence().isEmpty());
        }
    }
    @Test void authorizationFailureIsNeverRetried() {
        AtomicInteger count = new AtomicInteger();
        try (Fixture f = new Fixture(policy(6), (q, s) -> {
            count.incrementAndGet(); throw new AgentException("FORBIDDEN", "denied");
        })) {
            RunState run = f.run(); f.execute(run, c -> query());
            assertEquals("FORBIDDEN", run.snapshot().errorCode()); assertEquals(1, count.get());
        }
    }
    @Test void globalDeadlineStopsHostEvenWhenModelIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var policy = new LoopPolicy(6, Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMillis(100), 3, true);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Fixture f = new Fixture(policy)) {
            RunState run = f.run(); AtomicInteger terminal = new AtomicInteger(); run.onTerminal(s -> terminal.incrementAndGet());
            Future<?> task = worker.submit(() -> f.execute(run, c -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) {}
                }
                return Decision.clarify("late");
            }));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); task.get(2, TimeUnit.SECONDS);
            assertEquals("DEADLINE_EXCEEDED", run.snapshot().errorCode()); assertEquals(1, terminal.get());
            assertNull(run.snapshot().clarification());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void cancellationCanRevokeReservationAndCommitCannotBeOverwritten() {
        TurnControl cancelled = new TurnControl(); var ticket = cancelled.reserve();
        assertTrue(cancelled.interrupt(StopReason.USER_CANCELLED)); assertFalse(cancelled.commit(ticket, StopReason.ANSWER));
        TurnControl committed = new TurnControl(); var second = committed.reserve();
        assertTrue(committed.commit(second, StopReason.ANSWER)); assertFalse(committed.interrupt(StopReason.DEADLINE_EXCEEDED));
    }
}
