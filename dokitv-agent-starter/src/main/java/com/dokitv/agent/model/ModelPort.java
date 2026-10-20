package com.dokitv.agent.model;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.runtime.control.CallControl;

/** Implementations must set network timeouts and return a validated structured decision. */
public interface ModelPort {
    Decision decide(ModelContext context);
    default Decision decide(ModelContext context, CallControl control) { control.checkpoint(); return decide(context); }
    default Decision finalizeWithoutTools(ModelContext context, CallControl control) {
        if (!context.finalizing() || !context.availableTools().isEmpty()) throw new IllegalArgumentException("Finalization requires an empty tool catalog");
        return decide(context, control);
    }
}
