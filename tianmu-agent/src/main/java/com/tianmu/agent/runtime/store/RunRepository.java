package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.MemoryTypes;
import com.tianmu.agent.runtime.RunState;
import java.util.*;

public interface RunRepository {
    record Created(RunState run, boolean fresh) {}
    Created create(String message, AccessScope owner, UUID sessionId, String idempotencyKey, UUID replyToRunId);
    RunState load(UUID id, AccessScope caller);
    default MemoryTypes.View memory(RunState run) { return MemoryTypes.View.empty(); }
    default List<MemoryTypes.Message> messages(UUID sessionId, AccessScope caller, long before) { throw new com.tianmu.agent.core.AgentException("STORAGE_REQUIRED", "Message history requires persistent storage"); }
    default List<RunState> recoverQueued() { return List.of(); }
    default void refresh(RunState run) {}
    default boolean durable() { return false; }
    default com.tianmu.agent.core.DanmakuTypes.ReportRef saveReport(UUID runId, AccessScope owner, String evidenceId, byte[] html) {
        throw new com.tianmu.agent.core.AgentException("STORAGE_REQUIRED", "Report storage unavailable");
    }
    default byte[] report(UUID runId, AccessScope caller, String artifactId) { throw new com.tianmu.agent.core.AgentException("RESULT_NOT_FOUND", "Report not found"); }
    /** Read-only readiness check; durable adapters should verify their authority to accept writes. */
    default void verifyReady() {}
    /** Owner-only metadata used to authorize requested resources before loading their contents. */
    default Set<Long> resourcesForRun(UUID id, AccessScope caller) { return load(id, caller).owner.videoIds(); }
    default Set<Long> resourcesForSession(UUID id, AccessScope caller, long before) { return Set.of(); }
}
