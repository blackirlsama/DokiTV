package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentTypes.ToolResult;
import com.tianmu.agent.runtime.store.*;
import java.util.*;

/** Host-owned mutable execution data. Tools receive a restricted facade instead. */
public final class ExecutionState {
    private final Set<String> activeSkills = new HashSet<>();
    private final List<ToolResult> observations = new ArrayList<>();
    private final EvidenceStore evidence;
    public ExecutionState() { this(new InMemoryEvidenceStore()); }
    public ExecutionState(EvidenceStore evidence) { this.evidence = evidence; }
    public synchronized Set<String> activeSkills() { return Set.copyOf(activeSkills); }
    public synchronized void activateSkill(String name) { activeSkills.add(name); }
    public synchronized List<ToolResult> observations() { return List.copyOf(observations); }
    public synchronized void observe(ToolResult value) { observations.add(value); }
    public EvidenceStore evidence() { return evidence; }
}
