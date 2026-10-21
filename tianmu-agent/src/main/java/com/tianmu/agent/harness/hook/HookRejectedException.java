package com.tianmu.agent.harness.hook;

/** A gate may veto an operation; it cannot grant access or change runtime state. */
public final class HookRejectedException extends RuntimeException {
    public HookRejectedException() { super("Hook rejected operation"); }
}
