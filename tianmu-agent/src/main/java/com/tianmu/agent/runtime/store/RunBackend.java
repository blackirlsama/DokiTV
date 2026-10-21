package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.Snapshot;
import com.tianmu.agent.runtime.RunState;

/** The durable commit happens before the in-process projection becomes terminal. */
public interface RunBackend {
    RunBackend MEMORY = new RunBackend() {};
    default void save(RunState run) {}
    default void checkpoint(RunState run) {}
    default Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
        run.events().finish(event, data, done); return proposed;
    }
}
