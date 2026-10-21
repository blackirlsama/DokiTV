package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.Evidence;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryEvidenceStore implements EvidenceStore {
    private final Map<String, Evidence> values = new ConcurrentHashMap<>();
    public Evidence get(String id) { return values.get(id); }
    public void put(Evidence evidence) {
        if (values.putIfAbsent(evidence.id(), evidence) != null) throw new IllegalStateException("Duplicate evidence ID");
    }
    public Map<String, Evidence> snapshot() { return Map.copyOf(values); }
}
