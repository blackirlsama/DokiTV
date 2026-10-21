package com.tianmu.agent.harness.trace;

import java.util.concurrent.atomic.LongAdder;

/** Local operational counters; can later be bound to Micrometer without coupling the runtime. */
public final class TraceExportStats {
    public final LongAdder enqueued = new LongAdder();
    public final LongAdder delivered = new LongAdder();
    public final LongAdder enqueueFailures = new LongAdder();
    public final LongAdder deliveryFailures = new LongAdder();
    public final LongAdder quarantined = new LongAdder();
}
