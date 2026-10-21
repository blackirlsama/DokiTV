package com.dokitv.agent.runtime.store;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.core.MemoryTypes;
import com.dokitv.agent.runtime.RunState;
import java.util.*;

public interface RunRepository {
    record Created(RunState run, boolean fresh) {}
    Created create(String message, AccessScope owner, UUID sessionId, String idempotencyKey, UUID replyToRunId);
    RunState load(UUID id, AccessScope caller);
    default MemoryTypes.View memory(RunState run) { return MemoryTypes.View.empty(); }
    default List<MemoryTypes.Message> messages(UUID sessionId, AccessScope caller, long before) { throw new com.dokitv.agent.core.AgentException("STORAGE_REQUIRED", "Message history requires persistent storage"); }
    default List<RunState> recoverQueued() { return List.of(); }
    default void refresh(RunState run) {}
    default boolean durable() { return false; }
}
