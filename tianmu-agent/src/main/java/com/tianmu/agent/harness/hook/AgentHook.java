package com.tianmu.agent.harness.hook;

import java.util.Set;

/** Register a Spring bean to extend the harness. Hooks must be fast, thread-safe and non-blocking. */
public interface AgentHook {
    enum Mode { OBSERVER, GATE }
    String name();
    Set<HookPoint> points();
    default int order() { return 0; }
    default Mode mode() { return Mode.OBSERVER; }
    void onEvent(HookEvent event);
}
