package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.TaskTypes.PreparedTask;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.runtime.events.*;
import com.tianmu.agent.runtime.store.*;
import com.tianmu.agent.core.MemoryTypes;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Task state and terminal arbitration. Execution data, event storage and transport are separate. */
public class RunState {
    public final UUID id;
    public final UUID sessionId;
    public final long turnSeq;
    private final String executionQuery;
    private final RunBackend backend;
    private MemoryTypes.View memory = MemoryTypes.View.empty();
    private Snapshot restored;
    public final AccessScope owner;
    public final String message;
    public final Instant createdAt;
    public final LocalDate businessDate;
    private final ExecutionState execution;
    private final RunEventStore events;
    private Status status = Status.QUEUED;
    private int steps, modelCalls, preparationCalls, modelCallLimit = 16;
    private StopReason stopReason;
    private Result prepared;
    private PreparedTask task;
    private String traceId;
    private final TurnControl turnControl = new TurnControl();
    private final CancellationToken cancellation = new CancellationToken();
    private volatile Instant deadline;
    private boolean controlBound;
    private boolean persistedControl;
    private Answer answer;
    private String clarification, errorCode;
    private final List<Consumer<Snapshot>> terminalObservers = new ArrayList<>();

    public RunState(AccessScope owner, String message) { this(owner, message, Clock.systemUTC()); }
    public RunState(AccessScope owner, String message, Clock clock) {
        this(UUID.randomUUID(), UUID.randomUUID(), 1, owner, message, message, clock.instant(),
                clock.instant().plusSeconds(30), RunBackend.MEMORY, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), false);
        emit("PROGRESS", Map.of("run_id", id.toString(), "stage", "queued"));
    }
    public RunState(UUID id, UUID sessionId, long turnSeq, AccessScope owner, String message, String query,
                    Instant created, Instant deadline, RunBackend backend, RunEventStore events, EvidenceStore evidence, boolean restored) {
        this.id = id; this.sessionId = sessionId; this.turnSeq = turnSeq; this.owner = owner; this.message = message;
        this.executionQuery = query; this.createdAt = created; this.deadline = deadline; this.backend = backend;
        this.businessDate = created.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        this.events = events; this.execution = new ExecutionState(evidence); this.persistedControl = restored;
    }
    public synchronized void bindPersistedControl(int limit) { persistedControl = true; modelCallLimit = limit; }
    public String query() { return executionQuery; }
    public synchronized void memory(MemoryTypes.View value) { memory = Objects.requireNonNull(value); }
    public synchronized MemoryTypes.View memory() { return memory; }
    public synchronized void restore(Snapshot value) {
        restored = value; status = value.status(); steps = value.steps(); answer = value.answer(); clarification = value.clarification();
        errorCode = value.errorCode(); stopReason = value.stopReason(); modelCalls = value.modelCalls(); preparationCalls = value.preparationCalls();
        traceId = value.traceId(); task = value.task();
        if (task != null) modelCallLimit = task.maxModelCalls();
    }
    public void adoptTerminal(Snapshot value) {
        if (!com.tianmu.agent.core.AgentTypes.terminal(value.status())) return;
        List<Consumer<Snapshot>> observers;
        synchronized (this) {
            if (com.tianmu.agent.core.AgentTypes.terminal(status)) return;
            restore(value); turnControl.interrupt(value.stopReason()); observers = List.copyOf(terminalObservers); terminalObservers.clear();
        }
        cancellation.cancel(value.stopReason()); observers.forEach(observer -> notifyObserver(observer, value));
    }
    public ExecutionState execution() { return execution; }
    public RunEventStore events() { return events; }
    public synchronized Snapshot snapshot() {
        if (restored != null) return restored;
        return new Snapshot(id, status, createdAt, steps, answer, clarification, errorCode, traceId, stopReason, modelCalls,
                prepared == null ? null : prepared.route(), prepared == null || prepared.faq() == null ? null : prepared.faq().id(),
                prepared == null || prepared.faq() == null ? null : prepared.faq().version(), prepared == null ? null : prepared.request(), task,
                preparationCalls, preparationCalls + modelCalls, sessionId, turnSeq);
    }
    public synchronized void prepared(Result result) { checkpoint(); prepared = Objects.requireNonNull(result); backend.save(this); }
    public synchronized PreparedRequest preparedRequest() { return prepared == null ? restored == null ? null : restored.preparation() : prepared.request(); }
    public synchronized void task(PreparedTask value) {
        checkpoint();
        if (task != null || !value.runId().equals(id) || !value.scope().equals(owner) || !value.businessDate().equals(businessDate)
                || !value.deadline().equals(deadline) || value.maxModelCalls() != modelCallLimit) throw new IllegalStateException("Invalid prepared task binding");
        task = value; backend.save(this);
    }
    public synchronized PreparedTask task() { return task; }
    public synchronized void traceId(String value) {
        if (traceId != null && !traceId.equals(value)) throw new IllegalStateException("Trace is already bound");
        if (value.equals(traceId)) return;
        traceId = value; backend.save(this);
    }
    public CancellationToken cancellation() { return cancellation; }
    public Instant deadline() { return deadline; }
    public CallControl callControl() { return new CallControl(deadline, cancellation); }
    public synchronized boolean bindControl(Duration timeout) {
        return bindControl(timeout, 16);
    }
    public synchronized boolean bindControl(Duration timeout, int callLimit) {
        if (controlBound) return false;
        controlBound = true;
        if (!persistedControl) { deadline = createdAt.plus(timeout); modelCallLimit = callLimit; backend.save(this); }
        return true;
    }
    public synchronized int modelCallLimit() { return modelCallLimit; }
    public void modelCallStarted() { checkpoint(); synchronized (this) { reserveModelCall(); modelCalls++; backend.save(this); } }
    public void preparationCallStarted() { checkpoint(); synchronized (this) { reserveModelCall(); preparationCalls++; backend.save(this); } }
    private void reserveModelCall() {
        if (modelCalls + preparationCalls >= modelCallLimit) throw new AgentException("MODEL_CALL_BUDGET_EXCEEDED", "Request model-call budget exhausted");
    }
    public TurnControl.Reservation reserveFinalResponse() { return turnControl.reserve(); }
    public void onTerminal(Consumer<Snapshot> observer) {
        Snapshot completed;
        synchronized (this) {
            if (!com.tianmu.agent.core.AgentTypes.terminal(status)) { terminalObservers.add(observer); return; }
            completed = snapshot();
        }
        notifyObserver(observer, completed);
    }
    public synchronized boolean start() {
        if (status != Status.QUEUED) return false;
        status = Status.RUNNING; restored = null; backend.save(this); emit("PROGRESS", Map.of("stage", "running")); return true;
    }
    public void checkpoint() {
        cancellation.checkpoint();
        backend.checkpoint(this);
        cancellation.checkpoint();
        if (!Instant.now().isBefore(deadline)) { timeout(); cancellation.checkpoint(); }
        if (turnControl.state().phase() == TurnControl.Phase.COMMITTED)
            throw new AgentException("RUN_TERMINATED", "Run already reached a terminal decision");
    }
    public synchronized void nextStep() { steps++; backend.save(this); }
    public void emit(String type, Object data) { events.append(type, data); }
    public void succeed(Answer value) { succeed(reserveFinalResponse(), value, StopReason.ANSWER); }
    public boolean succeed(TurnControl.Reservation reservation, Answer value, StopReason reason) {
        return publish(reservation, Status.SUCCEEDED, value, null, null, reason);
    }
    public void clarify(String text) { clarify(reserveFinalResponse(), text); }
    public boolean clarify(TurnControl.Reservation reservation, String text) {
        return publish(reservation, Status.NEEDS_INPUT, null, text, null, StopReason.CLARIFICATION);
    }
    public void fail(String code) {
        StopReason reason = switch (code) {
            case "DEADLINE_EXCEEDED" -> StopReason.DEADLINE_EXCEEDED;
            case "TOOL_FAILURE_LIMIT" -> StopReason.TOOL_FAILURE_LIMIT;
            case "STEP_LIMIT" -> StopReason.STEP_LIMIT;
            case "MODEL_CALL_BUDGET_EXCEEDED" -> StopReason.MODEL_CALL_BUDGET_EXCEEDED;
            case "NO_PROGRESS" -> StopReason.NO_PROGRESS;
            default -> StopReason.ERROR;
        };
        publish(null, Status.FAILED, null, null, code, reason);
    }
    public void timeout() { fail("DEADLINE_EXCEEDED"); }
    public void cancel() { cancel(StopReason.USER_CANCELLED); }
    public void cancel(StopReason reason) {
        if (!Set.of(StopReason.USER_CANCELLED, StopReason.SUPERSEDED, StopReason.SHUTDOWN).contains(reason))
            throw new IllegalArgumentException("Invalid cancellation reason");
        publish(null, Status.CANCELLED, null, null, null, reason);
    }
    private boolean publish(TurnControl.Reservation reservation, Status target, Answer value, String text, String code, StopReason reason) {
        List<Consumer<Snapshot>> observers; Snapshot completed; boolean won;
        synchronized (this) {
            if (com.tianmu.agent.core.AgentTypes.terminal(status)) return false;
            if (target == Status.SUCCEEDED || target == Status.NEEDS_INPUT) {
                var current = turnControl.state();
                if (reservation == null || current.phase() != TurnControl.Phase.RESERVED || !reservation.id().equals(current.reservation())) return false;
            }
            Object data = switch (target) {
                case SUCCEEDED -> value;
                case NEEDS_INPUT -> Map.of("message", text);
                case FAILED -> Map.of("code", code);
                case CANCELLED -> Map.of("reason", reason.name().toLowerCase(Locale.ROOT));
                default -> throw new IllegalArgumentException("Not terminal");
            };
            String event = switch (target) {
                case SUCCEEDED -> "ANSWER"; case NEEDS_INPUT -> "CLARIFICATION"; case FAILED -> "ERROR"; case CANCELLED -> "ABORT";
                default -> throw new IllegalArgumentException("Not terminal");
            };
            Snapshot base = snapshot();
            Snapshot proposed = new Snapshot(id, target, createdAt, steps, value, text, code, base.traceId(), reason, modelCalls,
                    base.route(), base.faqId(), base.faqVersion(), base.preparation(), task, preparationCalls, modelCalls + preparationCalls, sessionId, turnSeq);
            // No terminal status, cancellation or DONE is exposed before this durable transaction succeeds.
            completed = backend.commit(this, proposed, event, data,
                    Map.of("status", target.name(), "run_id", id.toString(), "stop_reason", reason.name()));
            won = completed.equals(proposed);
            restore(completed); turnControl.interrupt(completed.stopReason());
            observers = List.copyOf(terminalObservers); terminalObservers.clear();
        }
        if (completed.status() == Status.FAILED || completed.status() == Status.CANCELLED) cancellation.cancel(completed.stopReason());
        observers.forEach(observer -> notifyObserver(observer, completed));
        return won;
    }
    private void notifyObserver(Consumer<Snapshot> observer, Snapshot completed) {
        try { observer.accept(completed); }
        catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(RunState.class).warn("terminal_observer_failed run_id={} type={}", id, e.getClass().getSimpleName()); }
    }
}
