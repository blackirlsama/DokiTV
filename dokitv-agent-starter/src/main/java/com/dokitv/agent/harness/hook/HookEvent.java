package com.dokitv.agent.harness.hook;

import java.time.Instant;
import java.util.*;

/** No mutable run, credentials, prompt, business rows or model output are exposed to extensions. */
public record HookEvent(String schemaVersion, HookPoint point, UUID runId, String traceId,
                        String spanId, int step, Instant occurredAt, Map<String, Object> attributes) {
    public HookEvent { attributes = Map.copyOf(attributes); }
}
