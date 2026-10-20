package com.dokitv.agent.runtime;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.AgentTypes.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.dokitv.agent.harness.RuntimeHarness;
import com.dokitv.agent.runtime.control.StopReason;

@Service
public class RunService {
    private final Map<UUID, RunState> runs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final AgentRuntime runtime;
    private final RuntimeHarness harness;
    public RunService(AgentRuntime runtime, RuntimeHarness harness) { this.runtime = runtime; this.harness = harness; }
    public synchronized RunState create(String message, AccessScope owner) {
        // Demo retention is lazy and process-local; production must use a persistent RunRepository.
        runs.values().removeIf(run -> com.dokitv.agent.core.AgentTypes.terminal(run.snapshot().status())
                && run.createdAt.isBefore(Instant.now().minusSeconds(3600)));
        if (runs.size() >= 200) throw new AgentException("CAPACITY_EXCEEDED", "Demo run capacity reached");
        RunState run = new RunState(owner, message);
        runs.put(run.id, run);
        try {
            harness.start(run);
            executor.execute(() -> runtime.execute(run));
        }
        catch (AgentException e) { run.fail(e.code()); }
        catch (RejectedExecutionException e) { run.fail("QUEUE_FULL"); }
        return run;
    }
    public RunState owned(UUID id, AccessScope caller) {
        RunState run = runs.get(id);
        if (run == null || !run.owner.tenantId().equals(caller.tenantId())
                || !run.owner.userId().equals(caller.userId()))
            throw new AgentException("RUN_NOT_FOUND", "Run not found");
        if (!caller.videoIds().containsAll(run.owner.videoIds()))
            throw new AgentException("FORBIDDEN", "Access scope changed; old results cannot be read");
        io.opentelemetry.api.trace.Span.current().setAttribute("run.id", id.toString());
        return run;
    }
    @PreDestroy public void shutdown() {
        runs.values().forEach(run -> run.cancel(StopReason.SHUTDOWN));
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
