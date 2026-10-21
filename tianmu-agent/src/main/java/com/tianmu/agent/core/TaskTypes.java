package com.tianmu.agent.core;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.PreparedRequest;
import java.time.*;
import java.util.*;

public final class TaskTypes {
    private TaskTypes() {}
    public record Specification(String type, Map<String, Object> conditions, Map<String, String> provenance) {
        public Specification { conditions = ImmutablePayload.map(conditions); provenance = Map.copyOf(provenance); }
    }
    public record PreparedTask(UUID runId, String originalQuery, String query, LocalDate businessDate,
            String timezone, AccessScope scope, PreparedRequest preparation, Specification specification,
            Map<String, String> resourceVersions, Set<String> allowedTools, Instant deadline, int maxModelCalls) {
        public PreparedTask {
            resourceVersions = Map.copyOf(resourceVersions); allowedTools = Set.copyOf(allowedTools);
            Objects.requireNonNull(runId); Objects.requireNonNull(businessDate); Objects.requireNonNull(deadline);
            Objects.requireNonNull(originalQuery); Objects.requireNonNull(query); Objects.requireNonNull(scope);
            Objects.requireNonNull(preparation); Objects.requireNonNull(specification); Objects.requireNonNull(timezone);
            if (maxModelCalls < 1) throw new IllegalArgumentException("Invalid task budget");
        }
    }
}
