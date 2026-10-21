package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.core.StopReason;

@Service
public class RunService {
    private final Map<UUID, RunState> runs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final RequestOrchestrator orchestrator;
    private final RuntimeHarness harness;
    private final com.tianmu.agent.runtime.store.RunRepository repository;
    public RunService(RequestOrchestrator orchestrator, RuntimeHarness harness, com.tianmu.agent.runtime.store.RunRepository repository) { this.orchestrator = orchestrator; this.harness = harness; this.repository = repository; }
    public RunState create(String message, AccessScope owner) { return create(message, owner, null, null, null); }
    public synchronized RunState create(String message, AccessScope owner, UUID session, String key, UUID reply) {
        if (key != null && !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new AgentException("INVALID_REQUEST", "Invalid Idempotency-Key");
        // Demo retention is lazy and process-local; production must use a persistent RunRepository.
        runs.values().removeIf(run -> {
            boolean expired = com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status())
                    && (repository.durable() || run.createdAt.isBefore(Instant.now().minusSeconds(3600)));
            if (expired) harness.forget(run.id);
            return expired;
        });
        if (runs.values().stream().filter(r -> !com.tianmu.agent.core.AgentTypes.terminal(r.snapshot().status())).count() >= 200) throw new AgentException("CAPACITY_EXCEEDED", "Demo run capacity reached");
        var created = repository.create(message, owner, session, key, reply);
        if (!created.fresh()) return created.run();
        RunState run = created.run();
        runs.put(run.id, run);
        try {
            harness.start(run);
            executor.execute(() -> orchestrator.execute(run));
        }
        catch (AgentException e) { run.fail(e.code()); }
        catch (RejectedExecutionException e) { run.fail("QUEUE_FULL"); }
        return run;
    }
    public RunState owned(UUID id, AccessScope caller) {
        RunState run = repository.load(id, caller);
        if (run == null || !run.owner.tenantId().equals(caller.tenantId())
                || !run.owner.userId().equals(caller.userId()))
            throw new AgentException("RUN_NOT_FOUND", "Run not found");
        if (!caller.videoIds().containsAll(run.owner.videoIds()))
            throw new AgentException("FORBIDDEN", "Access scope changed; old results cannot be read");
        io.opentelemetry.api.trace.Span.current().setAttribute("run.id", id.toString());
        return run;
    }
    public java.util.List<com.tianmu.agent.core.MemoryTypes.Message> messages(UUID session, AccessScope owner, long before) { return repository.messages(session, owner, before); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void maintain() {
        try {
            runs.values().stream().filter(r -> !com.tianmu.agent.core.AgentTypes.terminal(r.snapshot().status())).forEach(repository::refresh);
            for (RunState run : repository.recoverQueued()) if (runs.putIfAbsent(run.id, run) == null) {
                harness.start(run); try { executor.execute(() -> orchestrator.execute(run)); }
                catch (RejectedExecutionException e) { run.fail("QUEUE_FULL"); }
            }
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(RunService.class).warn("storage_maintenance_failed type={}", e.getClass().getSimpleName()); }
    }
    @PreDestroy public void shutdown() {
        runs.values().forEach(run -> { try { run.cancel(StopReason.SHUTDOWN); } catch (Exception e) { /* Durable state is reconciled on restart. */ } });
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
