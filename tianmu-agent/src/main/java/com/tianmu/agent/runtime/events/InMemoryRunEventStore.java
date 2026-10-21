package com.tianmu.agent.runtime.events;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.Event;
import java.time.Instant;
import java.util.*;

/** Signals only schedule delivery; listeners must never perform network I/O on the writer thread. */
public final class InMemoryRunEventStore implements RunEventStore {
    private final List<Event> events = new ArrayList<>();
    private final Map<UUID, Runnable> listeners = new HashMap<>();
    private boolean closed;
    public void append(String type, Object data) {
        List<Runnable> signals;
        synchronized (this) { if (closed) return; add(type, data); signals = List.copyOf(listeners.values()); }
        signals.forEach(this::signal);
    }
    public void finish(String type, Object data, Object done) {
        List<Runnable> signals;
        synchronized (this) {
            if (closed) return;
            add(type, data); add("DONE", done); closed = true; signals = List.copyOf(listeners.values());
        }
        signals.forEach(this::signal);
    }
    private void add(String type, Object data) { events.add(new Event(events.size() + 1L, type, data, Instant.now())); }
    public synchronized Batch after(long cursor) {
        if (cursor < 0 || cursor > events.size()) throw new AgentException("INVALID_CURSOR", "Event cursor is outside this run");
        return new Batch(events.subList((int) cursor, events.size()), closed, events.size());
    }
    public synchronized Registration onChange(Runnable listener) {
        UUID id = UUID.randomUUID(); listeners.put(id, listener);
        return () -> { synchronized (InMemoryRunEventStore.this) { listeners.remove(id); } };
    }
    private void signal(Runnable listener) { try { listener.run(); } catch (RuntimeException ignored) {} }
}
