package com.dokitv.agent.access;

import com.dokitv.agent.core.AgentTypes.AccessScope;

/** Replace the demo credential with a verified identity from the team's authentication system. */
public interface IdentityPort {
    AccessScope authenticate(String credential);
}
