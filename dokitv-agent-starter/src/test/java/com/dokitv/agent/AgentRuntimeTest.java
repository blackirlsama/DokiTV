package com.dokitv.agent;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.demo.DemoAdapters;
import com.dokitv.agent.model.ModelPort;
import com.dokitv.agent.runtime.*;
import com.dokitv.agent.skill.SkillCatalog;
import com.dokitv.agent.tool.ToolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import com.dokitv.agent.harness.RuntimeHarness;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "harness.trace.directory=./target/test-traces/runtime")
@AutoConfigureMockMvc
class AgentRuntimeTest {
    @Autowired AgentRuntime runtime;
    @Autowired ToolDispatcher tools;
    @Autowired RunService runs;
    @Autowired MockMvc mvc;
    @Autowired RuntimeHarness harness;
    private AccessScope alice() { return new AccessScope("demo", "alice", Set.of(1001L)); }
    private RunState run(String message) { return new RunState(alice(), message); }
    private AgentRuntime with(ModelPort model) { return new AgentRuntime(model, tools, harness); }

    private Snapshot await(RunState run) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!com.dokitv.agent.core.AgentTypes.terminal(run.snapshot().status()) && System.nanoTime() < end) Thread.sleep(10);
        return run.snapshot();
    }
    @Test void httpEntryCanDirectlyDeliverFaq() throws Exception {
        String body = mvc.perform(post("/api/agent/runs").header("Authorization", "Bearer demo-alice")
                .contentType("application/json;charset=UTF-8").content("{\"message\":\"你能做什么\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("runId").asText());
        Snapshot snapshot = await(runs.owned(id, alice()));
        assertEquals(com.dokitv.agent.core.PreparationTypes.Route.FAQ, snapshot.route());
        assertEquals("demo-capabilities", snapshot.faqId()); assertEquals("1", snapshot.faqVersion());
        assertEquals(Status.SUCCEEDED, snapshot.status()); assertEquals(0, snapshot.modelCalls());
    }
    @Test void serviceEntryClarifiesBeforeHarness() throws Exception {
        Snapshot snapshot = await(runs.create("查询视频1001的弹幕数", alice()));
        assertEquals(Status.NEEDS_INPUT, snapshot.status());
        assertEquals(com.dokitv.agent.core.PreparationTypes.Route.CLARIFY, snapshot.route());
        assertEquals(0, snapshot.steps()); assertEquals(0, snapshot.modelCalls());
    }
    @Test void serviceEntryPassesPreparedQueryToHarnessWithOwnedEvidence() throws Exception {
        String message = "  查询视频1001昨天的弹幕数  ";
        RunState run = runs.create(message, alice()); Snapshot snapshot = await(run);
        assertEquals(Status.SUCCEEDED, snapshot.status());
        assertEquals(com.dokitv.agent.core.PreparationTypes.Route.HARNESS, snapshot.route());
        assertEquals(message, snapshot.preparation().originalQuery());
        assertEquals(message.strip(), snapshot.preparation().query());
        assertEquals(3, snapshot.steps()); assertFalse(snapshot.answer().evidenceIds().isEmpty());
    }

    @Test void demoCompletesWithOwnedEvidence() {
        RunState run = run("查询视频1001昨天的弹幕数");
        runtime.execute(run);
        assertEquals(Status.SUCCEEDED, run.snapshot().status());
        assertEquals(3, run.snapshot().steps());
        Evidence evidence = run.execution().evidence().get(run.snapshot().answer().evidenceIds().get(0));
        assertEquals(128L, evidence.data().value());
        assertTrue(evidence.data().demo());
        assertEquals(run.id, evidence.runId());
    }
    @Test void missingDateRequestsClarification() {
        RunState run = run("查询视频1001的弹幕数");
        runtime.execute(run);
        assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
    }
    @Test void outOfScopeVideoFails() {
        RunState run = run("查询视频1002昨天的弹幕数");
        runtime.execute(run);
        assertEquals("FORBIDDEN", run.snapshot().errorCode());
        assertTrue(run.execution().evidence().isEmpty());
    }
    @Test void toolCannotRunBeforeSkillActivation() {
        RunState run = run("x");
        with(ctx -> Decision.tool("query_video_metrics", Map.of())).execute(run);
        assertEquals("TOOL_NOT_AVAILABLE", run.snapshot().errorCode());
    }
    @Test void repeatedCallsStopLoop() {
        RunState run = run("x");
        with(ctx -> Decision.tool("list_skills", Map.of())).execute(run);
        assertEquals("NO_PROGRESS", run.snapshot().errorCode());
    }
    @Test void unknownEvidenceIsRejected() {
        RunState run = run("x");
        with(ctx -> Decision.answer("unsupported", List.of("ev_forged"))).execute(run);
        assertEquals("INVALID_EVIDENCE", run.snapshot().errorCode());
    }
    @Test void cancellationPreventsExecutionAndFinalAnswer() {
        RunState run = run("查询视频1001昨天的弹幕数");
        run.cancel();
        runtime.execute(run);
        run.succeed(new Answer("late answer", List.of()));
        assertEquals(Status.CANCELLED, run.snapshot().status());
        assertNull(run.snapshot().answer());
    }
    @Test void unrecognizedArgumentsCannotInjectScope() {
        RunState run = run("x");
        with(ctx -> Decision.tool("list_skills", Map.of("tenant_id", "other"))).execute(run);
        assertEquals("INVALID_ARGUMENTS", run.snapshot().errorCode());
    }
    @Test void evidenceCannotBeReadFromAnotherRun() {
        RunState first = run("查询视频1001昨天的弹幕数");
        runtime.execute(first);
        String evidenceId = first.snapshot().answer().evidenceIds().get(0);
        RunState second = run("x");
        with(ctx -> Decision.tool("read_result", Map.of("result_id", evidenceId))).execute(second);
        assertEquals("RESULT_NOT_FOUND", second.snapshot().errorCode());
    }
    @Test void invalidRequestAndIdentityAreRejected() throws Exception {
        mvc.perform(post("/api/agent/runs").contentType("application/json")
                .content("{\"message\":\"hello\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/runs").header("Authorization", "Bearer demo-alice")
                .contentType("application/json").content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void w3cTraceParentIsPropagatedToRunAndResponse() throws Exception {
        String traceId = "123456789012345678901234567890ab";
        mvc.perform(post("/api/agent/runs").header("Authorization", "Bearer demo-alice")
                .header("traceparent", "00-" + traceId + "-1234567890123456-01")
                .contentType("application/json").content("{\"message\":\"hello\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("X-Trace-ID", traceId))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
    @Test void statusAndSseDoNotExposeAnotherUsersRun() throws Exception {
        RunState run = runs.create("hello", alice());
        mvc.perform(get("/api/agent/runs/" + run.id).header("Authorization", "Bearer demo-bob"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/runs/" + run.id + "/events").header("Authorization", "Bearer demo-bob"))
                .andExpect(status().isNotFound());
    }
    @Test void completedRunReplaysSseEvents() throws Exception {
        RunState run = runs.create("hello", alice());
        for (int i = 0; i < 100 && !com.dokitv.agent.core.AgentTypes.terminal(run.snapshot().status()); i++)
            Thread.sleep(10);
        assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
        var request = mvc.perform(get("/api/agent/runs/" + run.id + "/events")
                .header("Authorization", "Bearer demo-alice")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:DONE")));
    }
}
