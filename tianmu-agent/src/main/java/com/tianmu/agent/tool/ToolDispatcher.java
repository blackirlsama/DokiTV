package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import org.springframework.stereotype.Component;
import java.time.DateTimeException;
import java.util.*;
import com.tianmu.agent.harness.hook.HookPoint;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.*;
import java.util.function.Function;

/** Fixed admission boundary; tool implementations register independently of this dispatcher. */
@Component
public class ToolDispatcher {
    private final SkillCatalog skills;
    private final RuntimeHarness harness;
    private final ToolRegistry registry;
    @org.springframework.beans.factory.annotation.Autowired
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry) {
        this.skills = skills; this.harness = harness; this.registry = registry;
    }
    public List<ToolDefinition> definitions(Set<String> activeSkills) { return registry.definitions(activeSkills); }
    public ToolResult execute(ToolCall call, RunState run) {
        run.checkpoint();
        AgentTool tool = registry.get(call.name());
        if (run.task() != null && !run.task().allowedTools().contains(call.name()))
            throw new AgentException("TOOL_NOT_AVAILABLE", "Tool is outside the prepared capability ceiling");
        if (tool.requiresSkill()) skills.checkTool(call.name(), run.execution().activeSkills());
        try {
            ToolArguments.validate(call, registry.definition(call.name()));
            ToolResult result = tool.execute(call, new HostContext(run, tool));
            run.checkpoint();
            if (result == null || !call.name().equals(result.tool())) throw new AgentException("INVALID_TOOL_RESULT", "Tool result does not match the registered handler");
            return result;
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new AgentException("INVALID_ARGUMENTS", "Tool arguments are invalid");
        }
    }
    private final class HostContext implements ToolContext {
        private final RunState run; private final AgentTool tool;
        HostContext(RunState run, AgentTool tool) { this.run = run; this.tool = tool; }
        public UUID runId() { return run.id; }
        public AccessScope scope() { return run.owner; }
        public LocalDate businessDate() { return run.businessDate; }
        public void checkpoint() { run.checkpoint(); }
        public List<Map<String, String>> listSkills() { return skills.list(); }
        public Skill activateSkill(String name) {
            if (!tool.canActivateSkills()) throw new AgentException("TOOL_CAPABILITY_DENIED", "Tool cannot activate skills");
            Skill skill = skills.read(name);
            return harness.stage(run, "agent.skill", HookPoint.PRE_SKILL, HookPoint.POST_SKILL,
                    Map.of("skill.name", name, "skill.version", skill.version()), () -> { run.execution().activateSkill(name); return skill; });
        }
        public List<ToolDefinition> definitionsForSkill(String name) {
            Skill skill = skills.read(name);
            return registry.definitions(run.execution().activeSkills()).stream().filter(d -> skill.tools().contains(d.name()))
                    .filter(d -> run.task() == null || run.task().allowedTools().contains(d.name())).toList();
        }
        public Evidence evidence(String id) {
            Evidence value = run.execution().evidence().get(id);
            if (value == null || !value.owner().equals(run.owner) || !value.runId().equals(run.id))
                throw new AgentException("RESULT_NOT_FOUND", "Result is not accessible in this run");
            return value;
        }
        public void checkQuery(MetricQuery query) {
            checkpoint();
            if (!scope().videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "Out-of-scope video");
            if (query.date().isBefore(businessDate().minusDays(30)) || !query.date().isBefore(businessDate()))
                throw new AgentException("DATE_OUT_OF_RANGE", "Only completed days within the previous 30 days are supported");
            if (run.task() != null && run.task().specification().type().equals("video.metric")) {
                Map<String, Object> expected = run.task().specification().conditions();
                if (((Number) expected.get("video_id")).longValue() != query.videoId() || !expected.get("date").equals(query.date().toString())
                        || !expected.get("metric").equals(query.metric())) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Tool query changed the prepared task conditions");
            }
        }
        public Evidence registerEvidence(MetricData data) {
            checkpoint();
            if (!data.complete() || !"Asia/Shanghai".equals(data.timezone()) || data.value() < 0)
                throw new AgentException("INVALID_EVIDENCE", "Query adapter returned incomplete or inconsistent evidence");
            checkQuery(new MetricQuery(data.videoId(), data.date(), data.metric()));
            Evidence value = new Evidence("ev_" + UUID.randomUUID(), run.id, run.owner, data);
            run.execution().evidence().put(value); return value;
        }
        public <T> T query(Function<CallControl, T> operation) { return harness.toolCall(run, operation); }
    }
}
