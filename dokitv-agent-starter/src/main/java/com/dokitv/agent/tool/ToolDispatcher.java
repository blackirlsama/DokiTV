package com.dokitv.agent.tool;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.query.BusinessQueryPort;
import com.dokitv.agent.runtime.RunState;
import com.dokitv.agent.skill.SkillCatalog;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.dokitv.agent.harness.RuntimeHarness;
import com.dokitv.agent.harness.hook.HookPoint;

@Component
public class ToolDispatcher {
    private final SkillCatalog skills;
    private final BusinessQueryPort queries;
    private final RuntimeHarness harness;
    public ToolDispatcher(SkillCatalog skills, BusinessQueryPort queries, RuntimeHarness harness) {
        this.skills = skills;
        this.queries = queries;
        this.harness = harness;
    }
    public List<ToolDefinition> definitions(Set<String> activeSkills) {
        List<ToolDefinition> definitions = new ArrayList<>();
        definitions.add(definition("list_skills", "List skill names and descriptions", Map.of()));
        definitions.add(definition("read_skill", "Read instructions and activate skill tools",
                Map.of("name", Map.of("type", "string", "enum", List.of("video-metrics")))));
        definitions.add(definition("read_result", "Read evidence owned by the current run",
                Map.of("result_id", Map.of("type", "string"))));
        if (activeSkills.contains("video-metrics")) definitions.add(definition("query_video_metrics",
                "Query a completed day of video bullet counts; only authorized videos are allowed",
                Map.of("video_id", Map.of("type", "integer", "minimum", 1),
                       "date", Map.of("type", "string", "format", "date"),
                       "metric", Map.of("type", "string", "enum", List.of("bullet_count")))));
        return List.copyOf(definitions);
    }
    private ToolDefinition definition(String name, String description, Map<String, Object> properties) {
        return new ToolDefinition(name, description, Map.of("type", "object", "properties", properties,
                "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false));
    }
    public ToolResult execute(ToolCall call, RunState run) {
        run.checkpoint();
        ToolResult result;
        try {
            result = switch (call.name()) {
                case "list_skills" -> {
                    exact(call.arguments(), Set.of());
                    yield new ToolResult(call.name(), Map.of("skills", skills.list()), null);
                }
                case "read_skill" -> {
                    exact(call.arguments(), Set.of("name"));
                    Skill skill = skills.read(text(call, "name"));
                    yield harness.stage(run, "agent.skill", HookPoint.PRE_SKILL, HookPoint.POST_SKILL,
                            Map.of("skill.name", skill.name(), "skill.version", skill.version()), () -> {
                        run.activeSkills.add(skill.name());
                        return new ToolResult(call.name(), Map.of("skill", skill, "tool_schemas",
                                definitions(run.activeSkills).stream().filter(t -> skill.tools().contains(t.name())).toList()), null);
                    });
                }
                case "read_result" -> {
                    exact(call.arguments(), Set.of("result_id"));
                    Evidence evidence = run.evidence.get(text(call, "result_id"));
                    if (evidence == null || !evidence.owner().equals(run.owner) || !evidence.runId().equals(run.id))
                        throw new AgentException("RESULT_NOT_FOUND", "Result is not accessible in this run");
                    yield new ToolResult(call.name(), Map.of("metric", evidence.data()), evidence.id());
                }
                case "query_video_metrics" -> metric(call, run);
                default -> throw new AgentException("TOOL_NOT_FOUND", "Tool is not registered");
            };
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new AgentException("INVALID_ARGUMENTS", "Tool arguments are invalid");
        }
        run.checkpoint();
        return result;
    }
    private ToolResult metric(ToolCall call, RunState run) {
        skills.checkTool(call.name(), run.activeSkills);
        exact(call.arguments(), Set.of("video_id", "date", "metric"));
        long videoId = new BigDecimal(call.arguments().get("video_id").toString()).longValueExact();
        if (!run.owner.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Out-of-scope video");
        LocalDate date = LocalDate.parse(text(call, "date"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (date.isBefore(today.minusDays(30)) || !date.isBefore(today))
            throw new AgentException("DATE_OUT_OF_RANGE", "Only completed days within the previous 30 days are supported");
        MetricQuery query = new MetricQuery(videoId, date, text(call, "metric"));
        MetricData data = harness.toolCall(run, control -> queries.query(query, run.owner, control));
        run.checkpoint();
        if (data.videoId() != videoId || !data.date().equals(date) || !data.metric().equals(query.metric())
                || !data.complete() || !data.timezone().equals("Asia/Shanghai"))
            throw new AgentException("INVALID_EVIDENCE", "Query adapter returned inconsistent or incomplete evidence");
        String evidenceId = "ev_" + UUID.randomUUID();
        run.evidence.put(evidenceId, new Evidence(evidenceId, run.id, run.owner, data));
        return new ToolResult(call.name(), Map.of("video_id", videoId, "date", date.toString(),
                "metric", data.metric(), "value", data.value(), "metric_version", data.metricVersion(),
                "source", data.source(), "data_as_of", data.dataAsOf().toString(),
                "complete", data.complete(), "demo", data.demo(), "evidence_id", evidenceId), evidenceId);
    }
    private static void exact(Map<String, Object> arguments, Set<String> expected) {
        if (!arguments.keySet().equals(expected)) throw new AgentException("INVALID_ARGUMENTS", "Unexpected or missing tool fields");
    }
    private static String text(ToolCall call, String field) {
        Object value = call.arguments().get(field);
        if (!(value instanceof String text) || text.isBlank()) throw new AgentException("INVALID_ARGUMENTS", "Expected nonempty text");
        return text;
    }
}
