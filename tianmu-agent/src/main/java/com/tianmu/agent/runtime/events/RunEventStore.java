package com.tianmu.agent.runtime.events;

import com.tianmu.agent.core.AgentTypes.Event;
import java.util.*;

/** Transport-free event port. Persistent implementations must define state/event transaction semantics. */
public interface RunEventStore {
    record Batch(List<Event> events, boolean closed, long lastSequence) { public Batch { events = List.copyOf(events); } }
    interface Registration extends AutoCloseable { @Override void close(); }
    void append(String type, Object data);
    void finish(String type, Object data, Object done);
    Batch after(long cursor);
    Registration onChange(Runnable signal);
}
