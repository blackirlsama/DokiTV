package com.tianmu.agent.harness.hook;

/** Gate-capable points run before side effects. Post/lifecycle hooks are observers only. */
public enum HookPoint {
    REQUEST_START(true), PRE_ROUTE(true), PRE_MODEL(true), POST_MODEL(false),
    PRE_TOOL(true), POST_TOOL(false), PRE_SKILL(true), POST_SKILL(false),
    PRE_DELIVER(true), POST_DELIVER(false), ON_ERROR(false), ON_CANCEL(false), REQUEST_END(false);
    private final boolean gateAllowed;
    HookPoint(boolean gateAllowed) { this.gateAllowed = gateAllowed; }
    public boolean gateAllowed() { return gateAllowed; }
}
