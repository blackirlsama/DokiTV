package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Explicitly ephemeral development mode; durable mode never falls back to this adapter. */
@Component
@ConditionalOnProperty(name="agent.storage.mode", havingValue="memory", matchIfMissing=true)
public final class MemoryRunRepository implements RunRepository {
    private final Map<UUID, RunState> runs = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();
    public synchronized Created create(String message, AccessScope owner, UUID session, String key, UUID reply) {
        runs.values().removeIf(r -> r.createdAt.isBefore(Instant.now().minusSeconds(3600)) && AgentTypes.terminal(r.snapshot().status()));
        keys.values().removeIf(id -> !runs.containsKey(id));
        if (runs.size() >= 200) throw new AgentException("CAPACITY_EXCEEDED", "Ephemeral run capacity reached");
        if (reply != null) throw new AgentException("STORAGE_REQUIRED", "Clarification resumption requires persistent storage");
        String identity = owner.tenantId() + ":" + owner.userId() + ":" + key;
        if (key != null && keys.containsKey(identity)) {
            RunState existing = load(keys.get(identity), owner);
            if (!existing.message.equals(message) || session != null && !session.equals(existing.sessionId))
                throw new AgentException("IDEMPOTENCY_CONFLICT", "Idempotency key has a different request");
            return new Created(existing, false);
        }
        if (session != null) {
            UUID requested = session;
            if (runs.values().stream().noneMatch(r -> r.sessionId.equals(requested) && r.owner.tenantId().equals(owner.tenantId()) && r.owner.userId().equals(owner.userId())))
                throw new AgentException("SESSION_NOT_FOUND", "Session not found");
        } else session = UUID.randomUUID();
        UUID finalSession = session;
        runs.values().stream().filter(r -> r.sessionId.equals(finalSession) && !AgentTypes.terminal(r.snapshot().status()))
                .forEach(r -> r.cancel(StopReason.SUPERSEDED));
        long turn = runs.values().stream().filter(r -> r.sessionId.equals(finalSession)).count() + 1;
        Instant now = Instant.now();
        RunState run = new RunState(UUID.randomUUID(), session, turn, owner, message, message, now, now.plusSeconds(30),
                RunBackend.MEMORY, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), false);
        run.emit("PROGRESS", Map.of("stage", "queued", "run_id", run.id.toString()));
        runs.put(run.id, run); if (key != null) keys.put(identity, run.id);
        return new Created(run, true);
    }
    public synchronized RunState load(UUID id, AccessScope caller) {
        RunState run = runs.get(id);
        if (run == null || !run.owner.tenantId().equals(caller.tenantId()) || !run.owner.userId().equals(caller.userId()))
            throw new AgentException("RUN_NOT_FOUND", "Run not found");
        if (!caller.videoIds().containsAll(run.owner.videoIds())) throw new AgentException("FORBIDDEN", "Historical scope is no longer accessible");
        return run;
    }
}
