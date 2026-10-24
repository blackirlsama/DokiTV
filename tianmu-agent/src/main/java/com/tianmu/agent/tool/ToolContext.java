package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Trusted host capabilities, without mutable RunState, terminal control, SSE, registry or Harness access. */
public interface ToolContext {
    UUID runId();
    AccessScope scope();
    LocalDate businessDate();
    void checkpoint();
    List<Map<String, String>> listSkills();
    Skill activateSkill(String name);
    default List<Map<String, Object>> skillResources(String name) { return List.of(); }
    default Map<String, Object> skillResource(String name, String resource, int offset, int limit) {
        throw new com.tianmu.agent.core.AgentException("SKILL_RESOURCE_NOT_FOUND", "Skill references are unavailable");
    }
    List<ToolDefinition> definitionsForSkill(String name);
    Evidence evidence(String id);
    Evidence registerEvidence(MetricData data);
    void checkQuery(MetricQuery query);
    <T> T query(Function<CallControl, T> operation);
}
