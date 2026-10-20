package com.dokitv.agent.harness.hook;

import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Example observer. Replace or add beans without editing the runtime. */
@Component
public class LifecycleLogHook implements AgentHook {
    private static final Logger log = LoggerFactory.getLogger("agent.lifecycle");
    public String name() { return "lifecycle-log"; }
    public Set<HookPoint> points() { return Set.of(HookPoint.REQUEST_START, HookPoint.REQUEST_END, HookPoint.ON_ERROR, HookPoint.ON_CANCEL); }
    public void onEvent(HookEvent event) {
        log.info("hook_point={} run_id={} step={}", event.point(), event.runId(), event.step());
    }
}
