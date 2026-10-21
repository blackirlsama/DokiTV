package com.dokitv.agent.runtime.control;

import com.dokitv.agent.core.StopReason;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Cancellation may preempt a reservation; commitment is the logical event-publication boundary. */
public final class TurnControl {
    public enum Phase { OPEN, RESERVED, COMMITTED }
    public record State(Phase phase, UUID reservation, StopReason reason) {}
    public record Reservation(UUID id) {}
    private final AtomicReference<State> state = new AtomicReference<>(new State(Phase.OPEN, null, null));
    public State state() { return state.get(); }
    public Reservation reserve() {
        State current = state.get();
        if (current.phase() != Phase.OPEN) return null;
        UUID id = UUID.randomUUID();
        return state.compareAndSet(current, new State(Phase.RESERVED, id, null)) ? new Reservation(id) : null;
    }
    public boolean commit(Reservation reservation, StopReason reason) {
        State current = state.get();
        return reservation != null && current.phase() == Phase.RESERVED && reservation.id().equals(current.reservation())
                && state.compareAndSet(current, new State(Phase.COMMITTED, reservation.id(), reason));
    }
    public boolean interrupt(StopReason reason) {
        while (true) {
            State current = state.get();
            if (current.phase() == Phase.COMMITTED) return false;
            if (state.compareAndSet(current, new State(Phase.COMMITTED, null, reason))) return true;
        }
    }
}
