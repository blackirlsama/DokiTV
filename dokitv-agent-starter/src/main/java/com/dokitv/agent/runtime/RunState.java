package com.dokitv.agent.runtime;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.AgentTypes.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.*;
import com.dokitv.agent.harness.HarnessSession;
import com.dokitv.agent.runtime.control.*;

/** Single worker per run; synchronization serializes cancellation, finalization and event replay. */
public class RunState {
    public final UUID id = UUID.randomUUID();
    public final AccessScope owner;
    public final String message;
    public final Instant createdAt = Instant.now();
    public final Set<String> activeSkills = new HashSet<>();
    public final List<ToolResult> observations = new ArrayList<>();
    public final Map<String, Evidence> evidence = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private final List<SseEmitter> listeners = new ArrayList<>();
    private Status status = Status.QUEUED;
    private int steps;
    private int modelCalls;
    private StopReason stopReason;
    private final TurnControl turnControl = new TurnControl();
    private final CancellationToken cancellation = new CancellationToken();
    private volatile Instant deadline = createdAt.plusSeconds(30);
    private boolean controlBound;
    private Answer answer;
    private String clarification;
    private String errorCode;
    private HarnessSession harnessSession;
    private boolean eventStreamClosed;
    private final List<Consumer<Snapshot>> terminalObservers = new ArrayList<>();

    public RunState(AccessScope owner, String message) {
        this.owner = owner;
        this.message = message;
        emit("PROGRESS", Map.of("run_id", id.toString(), "stage", "queued"));
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(id, status, createdAt, steps, answer, clarification, errorCode,
                harnessSession == null ? null : harnessSession.traceId(), stopReason, modelCalls);
    }
    public CancellationToken cancellation() { return cancellation; }
    public Instant deadline() { return deadline; }
    public CallControl callControl() { return new CallControl(deadline, cancellation); }
    public synchronized boolean bindControl(Duration timeout) {
        if (controlBound) return false;
        controlBound = true;
        deadline = createdAt.plus(timeout);
        return true;
    }
    public void modelCallStarted() { checkpoint(); synchronized (this) { modelCalls++; } }
    public TurnControl.Reservation reserveFinalResponse() { return turnControl.reserve(); }
    public synchronized HarnessSession harnessSession(Supplier<HarnessSession> factory) {
        if (harnessSession == null) harnessSession = factory.get();
        return harnessSession;
    }
    public void onTerminal(Consumer<Snapshot> observer) {
        Snapshot completed;
        synchronized (this) {
            if (!com.dokitv.agent.core.AgentTypes.terminal(status)) { terminalObservers.add(observer); return; }
            completed = snapshot();
        }
        notifyObserver(observer, completed);
    }
    public synchronized boolean start() {
        if (status != Status.QUEUED) return false;
        status = Status.RUNNING;
        emit("PROGRESS", Map.of("stage", "running"));
        return true;
    }
    public void checkpoint() {
        cancellation.checkpoint();
        if (!Instant.now().isBefore(deadline)) { timeout(); cancellation.checkpoint(); }
        if (turnControl.state().phase() == TurnControl.Phase.COMMITTED)
            throw new AgentException("RUN_TERMINATED", "Run already reached a terminal decision");
    }
    public synchronized void nextStep() { steps++; }
    public synchronized void emit(String type, Object data) {
        if (eventStreamClosed) return;
        Event event = new Event(events.size() + 1L, type, data, Instant.now());
        events.add(event);
        listeners.removeIf(listener -> !send(listener, event));
    }
    private boolean send(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type()).data(event));
            return true;
        } catch (IOException | IllegalStateException e) { return false; }
    }
    public synchronized SseEmitter subscribe(long after) {
        if (after < 0 || after > events.size())
            throw new AgentException("INVALID_CURSOR", "Event cursor is outside this run");
        SseEmitter emitter = new SseEmitter(60000L);
        emitter.onCompletion(() -> removeListener(emitter));
        emitter.onTimeout(() -> { removeListener(emitter); emitter.complete(); });
        emitter.onError(error -> removeListener(emitter));
        for (Event event : events) {
            if (event.sequence() > after && !send(emitter, event)) return emitter;
        }
        if (com.dokitv.agent.core.AgentTypes.terminal(status)) emitter.complete();
        else listeners.add(emitter);
        return emitter;
    }
    private synchronized void removeListener(SseEmitter emitter) { listeners.remove(emitter); }
    public void succeed(Answer value) { succeed(reserveFinalResponse(), value, StopReason.ANSWER); }
    public boolean succeed(TurnControl.Reservation reservation, Answer value, StopReason reason) {
        if (!turnControl.commit(reservation, reason)) return false;
        publish(Status.SUCCEEDED, value, null, null, reason);
        return true;
    }
    public void clarify(String text) { clarify(reserveFinalResponse(), text); }
    public boolean clarify(TurnControl.Reservation reservation, String text) {
        if (!turnControl.commit(reservation, StopReason.CLARIFICATION)) return false;
        publish(Status.NEEDS_INPUT, null, text, null, StopReason.CLARIFICATION);
        return true;
    }
    public void fail(String code) {
        StopReason reason = switch (code) {
            case "DEADLINE_EXCEEDED" -> StopReason.DEADLINE_EXCEEDED;
            case "TOOL_FAILURE_LIMIT" -> StopReason.TOOL_FAILURE_LIMIT;
            case "STEP_LIMIT" -> StopReason.STEP_LIMIT;
            case "NO_PROGRESS" -> StopReason.NO_PROGRESS;
            default -> StopReason.ERROR;
        };
        if (!turnControl.interrupt(reason)) return;
        cancellation.cancel(reason);
        publish(Status.FAILED, null, null, code, reason);
    }
    public void timeout() { fail("DEADLINE_EXCEEDED"); }
    public void cancel() { cancel(StopReason.USER_CANCELLED); }
    public void cancel(StopReason reason) {
        if (!Set.of(StopReason.USER_CANCELLED, StopReason.SUPERSEDED, StopReason.SHUTDOWN).contains(reason))
            throw new IllegalArgumentException("Invalid cancellation reason");
        if (!turnControl.interrupt(reason)) return;
        cancellation.cancel(reason);
        publish(Status.CANCELLED, null, null, null, reason);
    }
    private void publish(Status target, Answer value, String text, String code, StopReason reason) {
        List<Consumer<Snapshot>> observers;
        Snapshot completed;
        synchronized (this) {
            if (com.dokitv.agent.core.AgentTypes.terminal(status)) return;
            status = target;
            answer = value;
            clarification = text;
            errorCode = code;
            stopReason = reason;
            switch (target) {
                case SUCCEEDED -> emit("ANSWER", value);
                case NEEDS_INPUT -> emit("CLARIFICATION", Map.of("message", text));
                case FAILED -> emit("ERROR", Map.of("code", code));
                case CANCELLED -> emit("ABORT", Map.of("reason", reason.name().toLowerCase(Locale.ROOT)));
                default -> throw new IllegalArgumentException("Not a terminal state");
            }
            done();
            completed = snapshot();
            observers = new ArrayList<>(terminalObservers);
            terminalObservers.clear();
        }
        // Extensions run outside the state mutex; cancellation/GET cannot deadlock on a hook.
        observers.forEach(observer -> notifyObserver(observer, completed));
    }
    private void notifyObserver(Consumer<Snapshot> observer, Snapshot completed) {
        try { observer.accept(completed); }
        catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(RunState.class).warn("terminal_observer_failed run_id={} type={}", id, e.getClass().getSimpleName());
        }
    }
    private void done() {
        emit("DONE", Map.of("status", status.name(), "run_id", id.toString(), "stop_reason", stopReason.name()));
        eventStreamClosed = true;
        var closing = new ArrayList<>(listeners);
        listeners.clear();
        closing.forEach(SseEmitter::complete);
    }
}
