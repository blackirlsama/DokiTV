package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.resource.TextPage;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveToolsTest {
    @Test void harnessReadsOnlyRequestedReferenceAndThenPagesEvidence() {
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = fixture.run();
            fixture.execute(run, context -> {
                int step = context.observations().size();
                if (step == 0) {
                    assertEquals(3, context.availableTools().size());
                    return Decision.tool("read_skill", Map.of("name", "video-metrics"));
                }
                assertEquals(4, context.availableTools().size());
                if (step == 1) {
                    assertFalse(context.observations().get(0).data().toString().contains("# 弹幕指标口径"));
                    return Decision.tool("read_skill", Map.of("name", "video-metrics", "resource", "metric-semantics", "limit", 32));
                }
                if (step == 2) return Decision.tool("query_video_metrics", Map.of("video_id", 1001,
                        "date", context.businessDate().minusDays(1).toString(), "metric", "bullet_count"));
                ToolResult metric = context.observations().stream().filter(r -> r.tool().equals("query_video_metrics")).findFirst().orElseThrow();
                if (step == 3) return Decision.tool("read_result", Map.of("result_id", metric.evidenceId(), "limit", 32));
                assertEquals(4, step);
                assertTrue((Boolean) ((Map<?, ?>) context.observations().get(3).data().get("page")).get("has_more"));
                return Decision.answer("【模拟数据】弹幕数 128。", List.of(metric.evidenceId()));
            });
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertEquals(5, run.snapshot().steps());
            assertEquals(1, fixture.queries.get());
        }
    }
    @Test void preparedContentFingerprintRejectsSameVersionDifferentContent() {
        SkillCatalog skills = new SkillCatalog();
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = new com.tianmu.agent.runtime.RunState(new AccessScope("demo", "alice", Set.of(1001L)), "查询视频1001昨天的弹幕数");
            run.start();
            var prepared = new com.tianmu.agent.core.PreparationTypes.PreparedRequest(run.message, run.message,
                    com.tianmu.agent.core.PreparationTypes.Emotion.NEUTRAL, Set.of());
            run.prepared(new com.tianmu.agent.core.PreparationTypes.Result(
                    com.tianmu.agent.core.PreparationTypes.Route.HARNESS, prepared, null, null));
            var task = new com.tianmu.agent.application.TaskAssembler(skills, new com.tianmu.agent.prompt.PromptCatalog(), new ToolCatalog()).assemble(run);
            assertEquals(skills.fingerprint("video-metrics"), task.resourceVersions().get("skill-content:video-metrics"));
            var versions = new HashMap<>(task.resourceVersions());
            versions.put("skill-content:video-metrics", "changed-content");
            run.task(new com.tianmu.agent.core.TaskTypes.PreparedTask(task.runId(), task.originalQuery(), task.query(), task.businessDate(),
                    task.timezone(), task.scope(), task.preparation(), task.specification(), versions, task.allowedTools(), task.deadline(), task.maxModelCalls()));
            assertEquals("RESOURCE_VERSION_MISMATCH", assertThrows(AgentException.class, () -> fixture.tools.execute(
                    new ToolCall("read_skill", Map.of("name", "video-metrics")), run)).code());
            run.cancel();
        }
    }
    Resource fixture(String referencePath, String body) {
        String metadata = "{\"name\":\"reference-test\",\"version\":\"1.0.0\",\"description\":\"test\",\"tools\":[\"query_video_metrics\"],\"resources\":{\"details\":\"" + referencePath + "\"}}";
        return new ByteArrayResource(metadata.getBytes(StandardCharsets.UTF_8)) {
            public Resource createRelative(String path) {
                return new ByteArrayResource((path.equals("SKILL.md") ? "Read details only when needed" : body).getBytes(StandardCharsets.UTF_8));
            }
        };
    }
    @Test void skillReferencesAreDeclaredBoundedAndFingerprintIncludesContent() {
        SkillCatalog first = new SkillCatalog(new Resource[]{fixture("references/details.md", "first")});
        SkillCatalog changed = new SkillCatalog(new Resource[]{fixture("references/details.md", "second")});
        assertNotEquals(first.fingerprint("reference-test"), changed.fingerprint("reference-test"));
        assertEquals("first", first.resource("reference-test", "details", 0, 20).get("content"));
        assertEquals("SKILL_RESOURCE_NOT_FOUND", assertThrows(AgentException.class,
                () -> first.resource("reference-test", "../private", 0, 20)).code());
        assertThrows(IllegalStateException.class, () -> new SkillCatalog(new Resource[]{fixture("references/../../private.md", "x")}));
        assertThrows(IllegalStateException.class, () -> new SkillCatalog(new Resource[]{fixture("references/details.md", "x".repeat(262145))}));
    }
    @Test void referenceAccessRequiresRootSkillAndDoesNotAddAnotherTool() {
        SkillCatalog skills = new SkillCatalog();
        List<AgentTool> handlers = List.of(new ListSkillsTool(), new ReadSkillTool(), new ReadResultTool(), new QueryDanmakuTool((q, s, c) -> { throw new AssertionError("Unexpected live query"); }), new CreateDanmakuReportTool(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new QueryVideoMetricsTool((q, s) -> { throw new AssertionError("Unexpected business query"); }));
        ToolRegistry registry = new ToolRegistry(handlers, new ToolCatalog(), skills);
        assertEquals(6, new ToolCatalog().all().size());
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = fixture.run(); run.start();
            ToolDispatcher dispatcher = new ToolDispatcher(skills, fixture.harness, registry);
            var reference = new ToolCall("read_skill", Map.of("name", "video-metrics", "resource", "metric-semantics", "limit", 32));
            assertEquals("SKILL_REQUIRED", assertThrows(AgentException.class, () -> dispatcher.execute(reference, run)).code());
            var root = dispatcher.execute(new ToolCall("read_skill", Map.of("name", "video-metrics")), run);
            assertTrue(root.data().containsKey("resources"));
            var page = (Map<?, ?>) dispatcher.execute(reference, run).data().get("page");
            assertTrue((Boolean) page.get("has_more"));
            assertEquals(32, page.get("next_offset"));
            assertEquals("INVALID_ARGUMENTS", assertThrows(AgentException.class, () -> dispatcher.execute(
                    new ToolCall("read_skill", Map.of("name", "video-metrics", "offset", 0)), run)).code());
            assertEquals("INVALID_ARGUMENTS", assertThrows(AgentException.class, () -> dispatcher.execute(
                    new ToolCall("read_result", Map.of("result_id", "x", "limit", 4097)), run)).code());
            run.cancel();
        }
    }
    @Test void unicodePagesReconstructOriginalAndKeepStableHash() {
        String text = "中文😀文字🧪".repeat(20);
        StringBuilder read = new StringBuilder();
        int offset = 0;
        String hash = TextPage.hash(text);
        while (true) {
            var page = TextPage.read(text, offset, 3);
            assertEquals(hash, page.get("sha256"));
            read.append(page.get("content"));
            offset = (Integer) page.get("next_offset");
            if (!(Boolean) page.get("has_more")) break;
        }
        assertEquals(text, read.toString());
        assertEquals("", TextPage.read(text, offset, 3).get("content"));
        int beyond = offset + 1;
        assertThrows(AgentException.class, () -> TextPage.read(text, beyond, 3));
    }
    @Test void evidencePagingUsesOwnedOriginalWithoutChangingLegacyShape() throws Exception {
        var skills = new SkillCatalog();
        var registry = new ToolRegistry(List.of(new ListSkillsTool(), new ReadSkillTool(), new ReadResultTool(), new QueryDanmakuTool((q, s, c) -> { throw new AssertionError("Unexpected live query"); }), new CreateDanmakuReportTool(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new QueryVideoMetricsTool((q, s) -> { throw new AssertionError(); })), new ToolCatalog(), skills);
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = fixture.run(); run.start();
            MetricData metric = new MetricData(1001, run.businessDate.minusDays(1), "bullet_count", 128,
                    "v1", "中文😀".repeat(3000), Instant.now(), "Asia/Shanghai", true, true);
            Evidence original = new Evidence("ev_page", run.id, run.owner, metric);
            run.execution().evidence().put(original);
            ToolDispatcher dispatcher = new ToolDispatcher(skills, fixture.harness, registry);
            var automatic = dispatcher.execute(new ToolCall("read_result", Map.of("result_id", "ev_page")), run);
            assertTrue((Boolean) ((Map<?, ?>) automatic.data().get("page")).get("has_more"));
            MetricData small = new MetricData(1001, metric.date(), "bullet_count", 128, "v1", "fixture", metric.dataAsOf(), "Asia/Shanghai", true, true);
            run.execution().evidence().put(new Evidence("ev_small", run.id, run.owner, small));
            assertEquals(small, dispatcher.execute(new ToolCall("read_result", Map.of("result_id", "ev_small")), run).data().get("metric"));
            var wrongOwner = new Evidence("ev_other", UUID.randomUUID(), run.owner, metric);
            run.execution().evidence().put(wrongOwner);
            assertEquals("RESULT_NOT_FOUND", assertThrows(AgentException.class, () -> dispatcher.execute(
                    new ToolCall("read_result", Map.of("result_id", "ev_other", "offset", 0)), run)).code());
            StringBuilder content = new StringBuilder();
            int offset = 0;
            do {
                ToolResult result = dispatcher.execute(new ToolCall("read_result", Map.of("result_id", "ev_page", "offset", offset, "limit", 4096)), run);
                assertEquals(original.id(), result.evidenceId());
                var page = (Map<?, ?>) result.data().get("page");
                content.append(page.get("content"));
                offset = (Integer) page.get("next_offset");
                if (!(Boolean) page.get("has_more")) break;
            } while (true);
            var decoded = new ObjectMapper().readTree(content.toString());
            assertEquals(metric.source(), decoded.path("source").asText());
            assertEquals(128, decoded.path("value").asInt());
            assertEquals(metric, run.execution().evidence().get(original.id()).data());
            run.cancel();
        }
    }
}
