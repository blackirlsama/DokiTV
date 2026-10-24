package com.tianmu.agent.query;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

public interface DanmakuQueryPort {
    Dataset query(Query query, AccessScope scope, CallControl control);
}
