package com.tianmu.agent.core;

import java.time.*;
import java.util.*;
import com.tianmu.agent.core.TaskTypes.PreparedTask;
import com.tianmu.agent.core.PreparationTypes.*;

/** Shared contracts. Business adapters and model adapters depend on these types. */
public final class AgentTypes {
    private AgentTypes() {}
    public enum Status { QUEUED, RUNNING, SUCCEEDED, NEEDS_INPUT, FAILED, CANCELLED }
    public enum Kind { TOOL, ANSWER, CLARIFY }
    public record AccessScope(String tenantId, String userId, Set<Long> videoIds) {
        public AccessScope { videoIds = Set.copyOf(videoIds); }
    }
    public record ToolCall(String name, Map<String, Object> arguments) {
        public ToolCall { arguments = ImmutablePayload.map(arguments); }
    }
    public record Answer(String text, List<String> evidenceIds) {
        public Answer { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Decision(Kind kind, ToolCall call, Answer answer, String clarification) {
        public static Decision tool(String name, Map<String, Object> arguments) {
            return new Decision(Kind.TOOL, new ToolCall(name, arguments), null, null);
        }
        public static Decision answer(String text, List<String> evidenceIds) {
            return new Decision(Kind.ANSWER, null, new Answer(text, evidenceIds), null);
        }
        public static Decision clarify(String text) {
            return new Decision(Kind.CLARIFY, null, null, text);
        }
    }
    public record ModelContext(String message, LocalDate businessDate, AccessScope scope,
                               Set<String> activeSkills, List<ToolResult> observations,
                               List<ToolDefinition> availableTools, int remainingSteps,
                               boolean finalizing, String systemDirective, PreparedRequest preparation, PreparedTask task, MemoryTypes.View memory) {
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining, boolean finalizing, String directive, PreparedRequest preparation, PreparedTask task) {
            this(message, date, scope, skills, results, tools, remaining, finalizing, directive, preparation, task, MemoryTypes.View.empty());
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining, boolean finalizing, String directive, PreparedRequest preparation) {
            this(message, date, scope, skills, results, tools, remaining, finalizing, directive, preparation, null);
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining, boolean finalizing, String directive) {
            this(message, date, scope, skills, results, tools, remaining, finalizing, directive, null);
        }
        public ModelContext(String message, LocalDate date, AccessScope scope, Set<String> skills,
                            List<ToolResult> results, List<ToolDefinition> tools, int remaining) {
            this(message, date, scope, skills, results, tools, remaining, false, "");
        }
        public ModelContext {
            activeSkills = Set.copyOf(activeSkills);
            observations = List.copyOf(observations);
            availableTools = List.copyOf(availableTools);
        }
    }
    public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        public ToolDefinition { inputSchema = ImmutablePayload.map(inputSchema); }
    }
    public record ToolResult(String tool, Map<String, Object> data, String evidenceId, Answer terminalAnswer) {
        public ToolResult(String tool, Map<String, Object> data, String evidenceId) { this(tool, data, evidenceId, null); }
        public ToolResult { data = ImmutablePayload.map(data); }
        public ToolResult withData(Map<String, Object> value) { return new ToolResult(tool, value, evidenceId, terminalAnswer); }
    }
    public record Skill(String name, String version, String description,
                        String instructions, Set<String> tools) {
        public Skill { tools = Set.copyOf(tools); }
    }
    public record MetricQuery(long videoId, LocalDate date, String metric) {}
    public record MetricData(long videoId, LocalDate date, String metric, long value,
                             String metricVersion, String source, Instant dataAsOf,
                             String timezone, boolean complete, boolean demo) {}
    public record Evidence(String id, UUID runId, AccessScope owner, MetricData data) {}
    public record Event(long sequence, String type, Object data, Instant at) {}
    public record Snapshot(UUID runId, Status status, Instant createdAt, int steps,
                           Answer answer, String clarification, String errorCode, String traceId,
                           StopReason stopReason, int modelCalls, Route route, String faqId, String faqVersion, PreparedRequest preparation, PreparedTask task,
                           int preparationCalls, int totalModelCalls, UUID sessionId, long turnSeq) {}
    public static boolean terminal(Status status) {
        return status != Status.QUEUED && status != Status.RUNNING;
    }
}
