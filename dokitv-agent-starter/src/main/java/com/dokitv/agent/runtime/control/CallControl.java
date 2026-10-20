package com.dokitv.agent.runtime.control;

import java.time.Instant;

/** Do not serialize into prompts. Adapters should apply this deadline and register request cancellation. */
public record CallControl(Instant deadline, CancellationToken cancellation) {
    public void checkpoint() { cancellation.checkpoint(); }
}
