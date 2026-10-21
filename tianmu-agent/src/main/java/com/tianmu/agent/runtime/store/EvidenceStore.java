package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.Evidence;
import java.util.*;

public interface EvidenceStore {
    Evidence get(String id);
    void put(Evidence evidence);
    Map<String, Evidence> snapshot();
    default boolean isEmpty() { return snapshot().isEmpty(); }
}
