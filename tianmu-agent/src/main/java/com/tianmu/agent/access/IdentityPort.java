package com.tianmu.agent.access;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Replace the demo credential with a verified identity from the team's authentication system. */
public interface IdentityPort {
    AccessScope authenticate(String credential);
    default AccessScope forCreate(String credential, String message, java.util.UUID replyToRunId) { return authenticate(credential); }
    default AccessScope forRun(String credential, java.util.UUID runId) { return authenticate(credential); }
    default AccessScope forSession(String credential, java.util.UUID sessionId, long before) { return authenticate(credential); }
}
