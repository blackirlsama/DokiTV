package com.tianmu.agent.adapters.business;

import com.tianmu.agent.core.AgentException;
import java.time.*;
import java.util.*;

/** Raw tokens stay only in bounded process memory. Persisted scopes contain opaque random references. */
final class BusinessCredentials {
    private record Entry(String token, Instant expires) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    synchronized String register(String token) {
        Instant now = Instant.now(); entries.values().removeIf(value -> !value.expires().isAfter(now));
        for (var entry : entries.entrySet()) if (entry.getValue().token().equals(token)) {
            entry.setValue(new Entry(token, now.plusSeconds(600))); return entry.getKey();
        }
        if (entries.size() >= 1024) throw new AgentException("CAPACITY_EXCEEDED", "Credential context capacity reached");
        String reference = UUID.randomUUID().toString(); entries.put(reference, new Entry(token, now.plusSeconds(600))); return reference;
    }
    synchronized String token(String reference) {
        Entry entry = entries.get(reference);
        if (entry == null || !entry.expires().isAfter(Instant.now())) {
            entries.remove(reference); throw new AgentException("AUTH_CONTEXT_EXPIRED", "Submit a new authenticated request");
        }
        return entry.token();
    }
}
