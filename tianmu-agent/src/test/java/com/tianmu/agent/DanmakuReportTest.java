package com.tianmu.agent;

import com.tianmu.agent.application.TaskAssembler;
import com.tianmu.agent.application.ClarificationInputs;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.preparation.DanmakuReportTask;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.runtime.store.RunRepository;
import com.tianmu.agent.tool.ToolDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="harness.trace.directory=./target/test-traces/danmaku-report")
@AutoConfigureMockMvc
class DanmakuReportTest {
    @Autowired RunService runs;
    @Autowired RunRepository repository;
    @Autowired ToolDispatcher tools;
    @Autowired RuntimeHarness harness;
    @Autowired TaskAssembler assembler;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    final AccessScope alice = new AccessScope("demo", "alice", Set.of(1001L));
    String question() { return "分析直播间1001昨天的弹幕并生成报告"; }
    void await(RunState run) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(AgentTypes.terminal(run.snapshot().status()));
    }
    RunState prepared() {
        RunState run = repository.create(question(), alice, null, UUID.randomUUID().toString(), null).run();
        run.prepared(new Result(Route.HARNESS, new PreparedRequest(run.message, run.message, Emotion.NEUTRAL, Set.of(), Set.of(), Intent.DANMAKU_REPORT), null, null));
        run.task(assembler.assemble(run)); return run;
    }
    String artifact(RunState run) {
        return run.execution().observations().stream().filter(r -> r.tool().equals("create_danmaku_report")).findFirst().orElseThrow().data().get("artifact_id").toString();
    }
    @Test void fullOfflinePipelineGeneratesPrivateReportAndOneTerminalAnswer() throws Exception {
        RunState run = runs.create(question(), alice); await(run);
        assertEquals(Status.SUCCEEDED, run.snapshot().status(), run.snapshot().errorCode());
        assertEquals(Intent.DANMAKU_REPORT, run.preparedRequest().intent());
        assertEquals("danmaku.report", run.task().specification().type());
        assertEquals(StopReason.TOOL_DELIVERY, run.snapshot().stopReason());
        assertEquals(3, run.snapshot().modelCalls());
        Evidence evidence = run.execution().evidence().get(run.snapshot().answer().evidenceIds().get(0));
        assertEquals(120, evidence.analysis().totalMessages()); assertEquals(48, evidence.analysis().sampleOccurrenceCount());
        assertEquals(8, evidence.analysis().samples().size()); assertNull(evidence.data());
        String path = "/api/agent/runs/" + run.id + "/reports/" + artifact(run);
        String html = mvc.perform(get(path).header("Authorization", "Bearer demo-alice"))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("模拟数据")); assertTrue(html.contains("非随机")); assertTrue(html.contains("120"));
        assertEquals(8, html.split("class=\"sample-card\"", -1).length - 1);
        assertEquals(2, html.split("样本中出现 12 次", -1).length - 1);
        assertTrue(html.contains("48 条样本 · 去重后 8 种内容"));
        assertTrue(html.contains("发货时效咨询"));
        assertTrue(html.contains("优惠领取咨询"));
        assertTrue(html.contains("<svg class=\"trend-svg\""));
        assertEquals(12, html.split("<circle class=\"trend-dot\"", -1).length - 1);
        assertTrue(html.contains(">22</text>"), "Peak bucket should be visible in the simulated activity curve");
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer demo-bob")).andExpect(status().isNotFound());
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> repository.report(run.id, new AccessScope("demo", "alice", Set.of()), artifact(run))).code());
        assertEquals(1, run.events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
        assertTrue(run.events().after(0).events().stream().anyMatch(e -> e.type().equals("ARTIFACT_READY")));
    }
    @ParameterizedTest
    @ValueSource(strings = {"视频1001", "直播间1001", "视频ID：1001", "videoId=1001", "video_id:1001"})
    void targetAliasesShareMetricAndReportSemantics(String target) throws Exception {
        RunState metric = runs.create("查询" + target + "昨天的弹幕数", alice); await(metric);
        assertEquals(Status.SUCCEEDED, metric.snapshot().status(), metric.snapshot().errorCode());
        assertEquals(Intent.VIDEO_METRIC, metric.preparedRequest().intent());
        assertEquals(1001L, metric.task().specification().conditions().get("video_id"));
        assertTrue(metric.snapshot().answer().text().contains("128"));

        RunState report = runs.create("分析" + target + "昨天的弹幕并生成报告", alice); await(report);
        assertEquals(Status.SUCCEEDED, report.snapshot().status(), report.snapshot().errorCode());
        assertEquals(Intent.DANMAKU_REPORT, report.preparedRequest().intent());
        assertEquals("danmaku.report", report.task().specification().type());
        assertEquals(DanmakuReportTask.specification(DanmakuReportTask.parse(question(), report.businessDate)).conditions(), report.task().specification().conditions());
        String html = new String(repository.report(report.id, alice, artifact(report)), StandardCharsets.UTF_8);
        assertTrue(html.contains("videoId 1001"));
        assertFalse(html.contains("直播间弹幕分析报告"));
    }
    @Test void clarificationKeepsTheSameIdWhenVideoAndRoomNamesAreMixed() {
        LocalDate date = LocalDate.of(2026, 10, 24);
        String report = ClarificationInputs.combine("分析直播间1001的弹幕并生成报告", "videoId=1001 昨天", date);
        assertEquals(DanmakuReportTask.parse(question(), date), DanmakuReportTask.parse(report, date));
        String metric = ClarificationInputs.combine("查询直播间1001的弹幕数", "昨天", date);
        assertEquals(Map.of("video_id", "1001", "date", "2026-10-23", "metric", "bullet_count"), ClarificationInputs.confirmed(metric, date));
        assertEquals("1001", ClarificationInputs.confirmed("查询videoId=1001昨天的弹幕数", date).get("video_id"));
    }
    @Test void modelSectionsAreEscapedAndTheirSampleReferencesAreValidated() throws Exception {
        RunState run = prepared();
        String sections = json.writeValueAsString(List.of(Map.of("title", "<script>alert(1)</script>", "observation", "片段出现咨询 & 反馈", "recommendation", "核实问题", "sample_ids", List.of("100044"))));
        ModelPort model = context -> {
            if (!context.activeSkills().contains("danmaku-analysis")) return Decision.tool("read_skill", Map.of("name", "danmaku-analysis"));
            var evidence = context.observations().stream().filter(r -> r.tool().equals("query_danmaku")).findFirst();
            if (evidence.isEmpty()) return Decision.tool("query_danmaku", run.task().specification().conditions());
            return Decision.tool("create_danmaku_report", Map.of("evidence_id", evidence.get().evidenceId(), "sections_json", sections));
        };
        new AgentRuntime(model, tools, harness).execute(run);
        assertEquals(Status.SUCCEEDED, run.snapshot().status(), run.snapshot().errorCode());
        String html = new String(repository.report(run.id, alice, artifact(run)), StandardCharsets.UTF_8);
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>"));
        assertTrue(html.contains("href=\"#sample-100044\""));
        assertTrue(html.contains("id=\"sample-100044\""), "References to merged samples must remain navigable");
        RunState invalid = prepared(); invalid.start(); invalid.execution().activateSkill("danmaku-analysis");
        ToolResult query = tools.execute(new ToolCall("query_danmaku", invalid.task().specification().conditions()), invalid);
        assertEquals("INVALID_REPORT", assertThrows(AgentException.class, () -> tools.execute(new ToolCall("create_danmaku_report", Map.of("evidence_id", query.evidenceId(), "sections_json", sections.replace("100044", "999999"))), invalid)).code());
        invalid.cancel();
    }
    @Test void changedRoomOrTimeCannotEscapePreparedTaskAndEvidenceIsRunScoped() {
        RunState run = prepared(); run.start(); run.execution().activateSkill("danmaku-analysis");
        Map<String,Object> changed = new HashMap<>(run.task().specification().conditions()); changed.put("video_id",1002L);
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> tools.execute(new ToolCall("query_danmaku", changed), run)).code());
        changed.put("video_id",1001L); changed.put("start",LocalDate.now(DanmakuTypes.ZONE).minusDays(2).atStartOfDay().toString());
        assertThrows(RuntimeException.class, () -> tools.execute(new ToolCall("query_danmaku", changed), run));
        assertEquals("RESULT_NOT_FOUND", assertThrows(AgentException.class, () -> tools.execute(new ToolCall("create_danmaku_report", Map.of("evidence_id","ev_foreign","sections_json","[]")), run)).code());
        run.cancel();
    }
    @Test void rangesAndDatasetConsistencyAreValidated() {
        LocalDate day = LocalDate.of(2026,10,23);
        Query q = DanmakuReportTask.parse("分析直播间1001在2026-10-23 20:00到2026-10-23 22:00的弹幕报告",day.plusDays(1));
        assertEquals(Duration.ofHours(2),Duration.between(q.start(),q.end()));
        for (String invalid : List.of("分析直播间1001昨天20:00到22:00的弹幕报告", "分析直播间1001和直播间1002昨天弹幕报告", "分析直播间1001弹幕报告"))
            assertThrows(RuntimeException.class, () -> DanmakuReportTask.parse(invalid,day));
        List<Bucket> bins = new ArrayList<>(); for(int i=0;i<12;i++) bins.add(new Bucket(i,0));
        Dataset empty = new Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",false);
        assertTrue(empty.samples().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Dataset(q,1,0,bins,List.of(),Instant.now(),"fixture",false));
        assertThrows(IllegalArgumentException.class, () -> new Dataset(q,0,0,bins,List.of(new Sample("1",0,"x",false)),Instant.now(),"fixture",false));
    }
    @Test void emptyRoomGeneratesAnExplicitNoDataReportWithoutInventingFindings() {
        RunState run=prepared(); run.start(); run.execution().activateSkill("danmaku-analysis");
        Query query=DanmakuReportTask.parse(run.message,run.businessDate);
        List<Bucket> bins=new ArrayList<>(); for(int i=0;i<12;i++)bins.add(new Bucket(i,0));
        Dataset data=new Dataset(query,0,0,bins,List.of(),Instant.now(),"fixture",false);
        String id="ev_"+UUID.randomUUID();run.execution().evidence().put(new Evidence(id,run.id,alice,null,data));
        ToolResult result=tools.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id",id,"sections_json","[]")),run);
        String html=new String(repository.report(run.id,alice,result.data().get("artifact_id").toString()),StandardCharsets.UTF_8);
        assertTrue(html.contains("没有可用于内容分析的片段"));assertTrue(html.contains("弹幕 0 条"));
        assertFalse(html.contains("NaN")); assertFalse(html.contains("Infinity"));
        assertNotNull(result.terminalAnswer());run.cancel();
    }
    @Test void reportRequestCannotBeSilentlyCompletedAsPlainText() {
        RunState run=prepared();
        new AgentRuntime(context->Decision.answer("No report",List.of("invented")),tools,harness).execute(run);
        assertEquals(Status.FAILED,run.snapshot().status());assertEquals("REPORT_REQUIRED",run.snapshot().errorCode());
    }
}
