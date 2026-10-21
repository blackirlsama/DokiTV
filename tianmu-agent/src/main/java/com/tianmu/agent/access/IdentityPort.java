package com.tianmu.agent.access;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Replace the demo credential with a verified identity from the team's authentication system. */
public interface IdentityPort {
    AccessScope authenticate(String credential);
}
