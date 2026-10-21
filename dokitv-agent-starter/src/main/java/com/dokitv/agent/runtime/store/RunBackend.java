package com.dokitv.agent.runtime.store;

import com.dokitv.agent.core.AgentTypes.Snapshot;
import com.dokitv.agent.runtime.RunState;

/** The durable commit happens before the in-process projection becomes terminal. */
public interface RunBackend {
    RunBackend MEMORY = new RunBackend() {};
    default void save(RunState run) {}
    default void checkpoint(RunState run) {}
    default Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
        run.events().finish(event, data, done); return proposed;
    }
}
