package com.dokitv.agent.runtime.store;

import com.dokitv.agent.core.AgentTypes.Evidence;
import java.util.*;

public interface EvidenceStore {
    Evidence get(String id);
    void put(Evidence evidence);
    Map<String, Evidence> snapshot();
    default boolean isEmpty() { return snapshot().isEmpty(); }
}
