package com.tianmu.agent.core;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.time.LocalDate;
import java.util.*;

/** Request preparation is data-only: it cannot grant tools or change the authenticated scope. */
public final class PreparationTypes {
    private PreparationTypes() {}
    public enum Emotion { NEUTRAL, POSITIVE, NEGATIVE, UNKNOWN }
    public enum Route { FAQ, CLARIFY, HARNESS }
    public record Input(String query, LocalDate businessDate, AccessScope scope, MemoryTypes.View memory) {
        public Input(String query, LocalDate date, AccessScope scope) { this(query, date, scope, MemoryTypes.View.empty()); }
    }
    public record Clarification(boolean required, String question) {
        public Clarification {
            if (required && (question == null || question.isBlank() || question.length() > 2000))
                throw new IllegalArgumentException("A bounded clarification question is required");
            if (!required) question = null;
        }
    }
    /** Untrusted model candidates; PreparationService validates against server-derived task meaning. */
    public record Understanding(boolean needsClarification, String clarificationQuestion,
                                Map<String, Object> conditions, String rewrittenQuery) {
        public Understanding {
            conditions = ImmutablePayload.map(conditions);
            if (conditions.size() > 16 || conditions.entrySet().stream().anyMatch(e ->
                    !e.getKey().matches("[a-z][a-z0-9_]{0,63}") || e.getValue() == null
                    || !(e.getValue() instanceof String || e.getValue() instanceof Number || e.getValue() instanceof Boolean)
                    || e.getValue() instanceof String text && text.length() > 256))
                throw new IllegalArgumentException("Invalid understanding conditions");
            if (needsClarification) {
                new Clarification(true, clarificationQuestion);
                if (rewrittenQuery != null || !conditions.isEmpty())
                    throw new IllegalArgumentException("Clarification cannot carry executable candidates");
            } else if (clarificationQuestion != null) {
                throw new IllegalArgumentException("Executable understanding cannot request clarification");
            }
        }
        public static Understanding clarify(String question) { return new Understanding(true, question, Map.of(), null); }
        public static Understanding proceed(Map<String, Object> conditions, String query) {
            return new Understanding(false, null, conditions, query);
        }
    }
    public record FaqAnswer(String id, String version, String text) {
        public FaqAnswer {
            if (id == null || !id.matches("[a-zA-Z0-9_.-]{1,80}") || version == null || !version.matches("[a-zA-Z0-9_.-]{1,40}")
                    || text == null || text.isBlank() || text.length() > 8000)
                throw new IllegalArgumentException("Invalid curated FAQ entry");
        }
    }
    public record PreparedRequest(String originalQuery, String query, Emotion emotion, Set<String> degradedStages, Set<String> skippedStages) {
        public PreparedRequest(String originalQuery, String query, Emotion emotion, Set<String> degraded) { this(originalQuery, query, emotion, degraded, Set.of()); }
        public PreparedRequest { degradedStages = Set.copyOf(degradedStages); skippedStages = Set.copyOf(skippedStages); }
    }
    public record Result(Route route, PreparedRequest request, FaqAnswer faq, String clarification) {}
}
