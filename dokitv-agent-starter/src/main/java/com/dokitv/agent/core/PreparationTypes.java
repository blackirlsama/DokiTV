package com.dokitv.agent.core;

import com.dokitv.agent.core.AgentTypes.AccessScope;
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
