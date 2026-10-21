package com.dokitv.agent.runtime.control;

import com.dokitv.agent.core.StopReason;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.runtime.RunState;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Independent watchdog and bounded blocking-call workers; neither depends on another model turn. */
public final class LoopControl implements AutoCloseable {
    private final LoopPolicy policy;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> daemon(task, "harness-deadline"));
    private final ThreadPoolExecutor calls = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> daemon(task, "harness-call"), new ThreadPoolExecutor.AbortPolicy());
    public LoopControl(LoopPolicy policy) { this.policy = policy; timer.setRemoveOnCancelPolicy(true); }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    public LoopPolicy policy() { return policy; }
    public void arm(RunState run) {
        if (!run.bindControl(policy.requestTimeout(), policy.maxModelCalls())) return;
        long millis = Math.max(0, Duration.between(Instant.now(), run.deadline()).toMillis());
        ScheduledFuture<?> deadline = timer.schedule(run::timeout, millis, TimeUnit.MILLISECONDS);
        run.onTerminal(snapshot -> deadline.cancel(false));
    }
    public <T> T call(RunState run, Duration timeout, String timeoutCode, String failureCode,
                       Function<Runnable, Runnable> contextual, Function<CallControl, T> operation) {
        arm(run);
        run.checkpoint();
        Instant localDeadline = Instant.now().plus(timeout);
        CallControl control = new CallControl(localDeadline.isBefore(run.deadline()) ? localDeadline : run.deadline(), new CancellationToken());
        FutureTask<T> task = new FutureTask<>(() -> operation.apply(control));
        try (var registration = run.cancellation().onCancel(() -> {
            control.cancellation().cancel(run.cancellation().reason()); task.cancel(true);
        })) {
            try { calls.execute(contextual.apply(task)); }
            catch (RejectedExecutionException e) { throw new AgentException("CALL_CAPACITY_EXCEEDED", "Blocking-call workers are full"); }
            long remaining = Duration.between(Instant.now(), run.deadline()).toMillis();
            if (remaining <= 0) { run.timeout(); run.checkpoint(); }
            try {
                T value = task.get(Math.max(1, Math.min(remaining, timeout.toMillis())), TimeUnit.MILLISECONDS);
                run.checkpoint();
                return value;
            } catch (TimeoutException e) {
                control.cancellation().cancel(StopReason.ADAPTER_TIMEOUT);
                task.cancel(true);
                if (!Instant.now().isBefore(run.deadline())) { run.timeout(); run.checkpoint(); }
                throw new AgentException(timeoutCode, "Adapter call exceeded its time budget");
            } catch (CancellationException e) {
                run.checkpoint();
                throw new AgentException("CANCELLED", "Adapter call cancelled");
            } catch (InterruptedException e) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                run.cancel(StopReason.SHUTDOWN);
                throw new AgentException("CANCELLED", "Execution interrupted");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AgentException domain) throw domain;
                throw new AgentException(failureCode, "Adapter call failed");
            }
        }
    }
    @Override public void close() { timer.shutdownNow(); calls.shutdownNow(); }
}
