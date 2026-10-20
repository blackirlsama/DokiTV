package com.dokitv.agent.query;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.runtime.control.CallControl;

/** The only business-data boundary. Scope comes from trusted server-side identity. */
public interface BusinessQueryPort {
    MetricData query(MetricQuery query, AccessScope scope);
    default MetricData query(MetricQuery query, AccessScope scope, CallControl control) {
        control.checkpoint();
        return query(query, scope);
    }
}
