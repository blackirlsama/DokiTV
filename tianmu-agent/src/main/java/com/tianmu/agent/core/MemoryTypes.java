package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

public final class MemoryTypes {
    private MemoryTypes() {}
    public record Message(UUID id, UUID runId, long sequence, String role, String text, Instant at) {}
    public record View(long version, String olderSummary, List<Message> recent, boolean truncated, String boundary) {
        public View { recent = List.copyOf(recent); }
        public static View empty() { return new View(0, "", List.of(), false, ""); }
    }
}
