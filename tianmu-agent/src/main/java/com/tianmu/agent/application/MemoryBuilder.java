package com.tianmu.agent.application;

import com.tianmu.agent.core.MemoryTypes.*;
import java.util.*;

/** A bounded model view, never a mutation of the stored conversation. */
public final class MemoryBuilder {
    private final String boundary;
    public MemoryBuilder() { this(new com.tianmu.agent.prompt.PromptCatalog().text("common.current-focus-boundary")); }
    public MemoryBuilder(String boundary) { this.boundary = Objects.requireNonNull(boundary); }
    public View build(long version, List<Message> source) {
        List<Message> ordered = source.stream().sorted(Comparator.comparingLong(Message::sequence)).toList();
        if (ordered.isEmpty()) return new View(version, "", List.of(), false, boundary);
        int start = Math.max(0, ordered.size() - 8); if (start % 2 != 0) start++;
        List<Message> recent = new ArrayList<>(ordered.subList(start, ordered.size()));
        StringBuilder older = new StringBuilder();
        for (Message message : ordered.subList(0, start)) {
            String line = message.role() + " [" + message.id() + "]: " + clip(message.text(), 500) + "\n";
            if (older.length() + line.length() > 4000) break;
            older.append(line);
        }
        boolean truncated = start > 0;
        while (!recent.isEmpty() && recent.stream().mapToInt(m -> m.text().length()).sum() > 16000) {
            recent.remove(0); if (!recent.isEmpty()) recent.remove(0); truncated = true;
        }
        int remaining = 16000 - recent.stream().mapToInt(m -> m.text().length()).sum();
        if (older.length() > remaining) {
            int end = Math.max(0, remaining);
            if (end > 0 && Character.isHighSurrogate(older.charAt(end - 1))) end--;
            older.setLength(end); truncated = true;
        }
        return new View(version, older.toString(), recent, truncated, boundary);
    }
    private String clip(String text, int max) {
        if (text.length() <= max) return text;
        int end = max; if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + " [excerpt]";
    }
}
