package com.tianmu.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.RunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="harness.trace.directory=./target/test-traces/details-integration")
@AutoConfigureMockMvc
class TraceDetailIntegrationTest {
    static final List<TraceEnvelope> exported = new CopyOnWriteArrayList<>();
    @TestConfiguration static class Sink {
        @Bean TraceSink captureSink() { return (envelope, raw) -> exported.add(envelope); }
    }
    @Autowired RunService runs;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Test void snapshotsFollowAsyncModelAndToolCallsAndRequireCurrentRunOwnership() throws Exception {
        var run = runs.create("分析直播间1001昨天的弹幕并生成报告", new AccessScope("demo", "alice", Set.of(1001L)));
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (exported.stream().noneMatch(s -> s.name().equals("agent.run") && run.id.toString().equals(s.attributes().get("run.id"))) && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(Status.SUCCEEDED, run.snapshot().status());
        var spans = exported.stream().filter(s -> run.id.toString().equals(s.attributes().get("run.id"))).toList();
        var query = spans.stream().filter(s -> s.name().equals("agent.tool") && "query_danmaku".equals(s.attributes().get("tool.name"))).findFirst().orElseThrow();
        assertEquals("TOOL", query.attributes().get("node.type"));
        assertEquals("SUCCEEDED", query.attributes().get("node.status"));
        String path = "/api/agent/runs/" + run.id + "/trace-payloads/" + query.attributes().get("detail.output_ref");
        String body = mvc.perform(get(path).header("Authorization", "Bearer demo-alice")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(8, json.readTree(body).path("value").path("data").path("analysis").path("samples").size());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer demo-bob")).andExpect(status().isNotFound());
        var model = spans.stream().filter(s -> s.name().equals("agent.model")).findFirst().orElseThrow();
        assertEquals("NOT_REPORTED", model.attributes().get("model.usage_state"));
        assertFalse(model.attributes().containsKey("model.input_tokens"));
        assertNotNull(model.attributes().get("detail.input_ref"));
        assertNotNull(model.attributes().get("execution.queue_ms"));
        assertTrue(spans.stream().allMatch(s -> !s.attributes().containsValue("这款什么时候发货？")));
    }
}
