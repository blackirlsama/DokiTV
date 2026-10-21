package com.tianmu.agent.runtime.control;

import com.tianmu.agent.core.AgentException;

/** Read-only adapters may explicitly classify a transient failure. Do not use this for auth/validation failures. */
public final class TransientToolException extends AgentException {
    public TransientToolException() { super("TOOL_TEMPORARY_ERROR", "Read-only tool temporarily unavailable"); }
}
