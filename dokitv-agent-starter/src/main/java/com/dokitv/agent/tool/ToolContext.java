package com.dokitv.agent.tool;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.runtime.control.CallControl;
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
    List<ToolDefinition> definitionsForSkill(String name);
    Evidence evidence(String id);
    Evidence registerEvidence(MetricData data);
    void checkQuery(MetricQuery query);
    <T> T query(Function<CallControl, T> operation);
}
