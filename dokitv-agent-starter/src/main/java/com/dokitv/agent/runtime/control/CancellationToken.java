package com.dokitv.agent.runtime.control;

import com.dokitv.agent.core.AgentException;
import java.util.*;

/** Shared host/adaptor cancellation signal. Callbacks must be quick and must not do network I/O. */
public final class CancellationToken {
    public interface Registration extends AutoCloseable { @Override void close(); }
    private StopReason reason;
    private final Map<UUID, Runnable> callbacks = new HashMap<>();
    public synchronized boolean cancelled() { return reason != null; }
    public synchronized StopReason reason() { return reason; }
    public void cancel(StopReason value) {
        List<Runnable> notify;
        synchronized (this) {
            if (reason != null) return;
            reason = value;
            notify = new ArrayList<>(callbacks.values());
            callbacks.clear();
        }
        for (Runnable callback : notify) { try { callback.run(); } catch (RuntimeException ignored) {} }
    }
    public Registration onCancel(Runnable callback) {
        UUID id = UUID.randomUUID();
        synchronized (this) {
            if (reason == null) { callbacks.put(id, callback); return () -> remove(id); }
        }
        callback.run();
        return () -> {};
    }
    private synchronized void remove(UUID id) { callbacks.remove(id); }
    public void checkpoint() {
        StopReason value = reason();
        if (value == null) return;
        throw new AgentException(value == StopReason.DEADLINE_EXCEEDED ? "DEADLINE_EXCEEDED" : "CANCELLED", "Execution was stopped");
    }
}
