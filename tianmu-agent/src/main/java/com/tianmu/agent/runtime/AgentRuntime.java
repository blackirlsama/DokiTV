package com.tianmu.agent.runtime;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.tool.ToolDispatcher;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.runtime.control.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.harness.trace.*;

@Component
public class AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final Set<String> RETRYABLE = Set.of("TOOL_CALL_TIMEOUT", "TOOL_TEMPORARY_ERROR");
    private final String finalizeDirective;
    private final ModelPort model;
    private final ToolDispatcher tools;
    private final com.tianmu.agent.application.DeliveryService delivery;
    private final RuntimeHarness harness;
    private static final class Progress {
        final Set<ToolCall> successfulCalls = new HashSet<>();
        String failingTool;
        int consecutiveFailures;
        void success(ToolCall call) { successfulCalls.add(call); failingTool = null; consecutiveFailures = 0; }
        int failure(String name) {
            consecutiveFailures = name.equals(failingTool) ? consecutiveFailures + 1 : 1;
            failingTool = name;
            return consecutiveFailures;
        }
    }
    public AgentRuntime(ModelPort model, ToolDispatcher tools, RuntimeHarness harness) {
        this(model, tools, harness, new PromptCatalog());
    }
    public AgentRuntime(ModelPort model, ToolDispatcher tools, RuntimeHarness harness, PromptCatalog prompts) {
        this(model, tools, harness, prompts, new com.tianmu.agent.application.DeliveryService(harness));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentRuntime(ModelPort model, ToolDispatcher tools, RuntimeHarness harness, PromptCatalog prompts, com.tianmu.agent.application.DeliveryService delivery) {
        this.model = model; this.tools = tools; this.harness = harness; this.delivery = delivery;
        this.finalizeDirective = prompts.text("harness.finalize");
    }
    public void execute(RunState run) {
        try { harness.execute(run, () -> loop(run)); }
        catch (AgentException e) { run.fail(e.code()); }
        catch (Exception e) {
            log.error("run_id={} failed type={}", run.id, e.getClass().getSimpleName());
            run.fail("INTERNAL_ERROR");
        }
    }
    private void loop(RunState run) {
        if (!run.start()) return;
        String input = harness.stage(run, "agent.input", null, null, Map.of(),
                () -> harness.transform(run, TransformPoint.INPUT, run.message));
        harness.stage(run, "agent.route", HookPoint.PRE_ROUTE, null, Map.of(), () -> true);
        runLoop(run, input);
    }
    void runPrepared(RunState run, com.tianmu.agent.core.TaskTypes.PreparedTask task) {
        if (run.task() != task) throw new IllegalStateException("Prepared task must be bound before execution");
        runLoop(run, task.query());
    }
    private void runLoop(RunState run, String message) {
        List<String> available = tools.definitions(run.execution().activeSkills()).stream()
                .filter(tool -> run.task() == null || run.task().allowedTools().contains(tool.name()))
                .map(ToolDefinition::name).toList();
        Map<String, Object> start = new HashMap<>();
        start.put("agent.max_turns", harness.policy().maxTurns());
        start.put("agent.available_tools", available.stream().limit(32).toList());
        start.put("agent.available_tool_count", available.size());
        if (model.modelName() != null) start.put("model.name", model.modelName());
        harness.marker(run, SpanType.AGENT_START, start);
        boolean returned = false;
        try { runLoopBody(run, message); returned = true; }
        finally {
            Map<String, Object> end = new HashMap<>();
            end.put("agent.total_turns", run.snapshot().steps());
            end.put("agent.reached_turn_limit", run.snapshot().steps() >= harness.policy().maxTurns());
            end.put("agent.outcome", !returned ? "ERROR" : run.snapshot().status().name());
            TraceUsage usage = io.opentelemetry.context.Context.current().get(TraceUsage.KEY);
            if (usage != null) end.putAll(usage.totals());
            harness.marker(run, SpanType.AGENT_END, end);
        }
    }
    private void runLoopBody(RunState run, String message) {
        run.emit("PROGRESS", Map.of("stage", "harness"));
        Progress progress = new Progress();
        int max = harness.policy().maxTurns();
        for (int step = 0; step < max; step++) {
            run.checkpoint();
            run.nextStep();
            final int remaining = max - step;
            boolean done = harness.stage(run, "agent.loop", null, null, Map.of(),
                    () -> iteration(run, message, remaining, progress));
            if (done) return;
        }
        if (!harness.policy().finalizeEnabled() || run.execution().evidence().isEmpty()) { run.fail("STEP_LIMIT"); return; }
        finalizeWithoutTools(run, message);
    }
    private ModelContext context(RunState run, String message, int remaining, boolean finalizing) {
        return harness.stage(run, "agent.context", null, null, Map.of("context.observation_count", run.execution().observations().size()),
                () -> harness.transform(run, TransformPoint.CONTEXT, new ModelContext(message,
                        run.businessDate, run.owner, run.execution().activeSkills(), run.execution().observations(),
                        finalizing ? List.of() : tools.definitions(run.execution().activeSkills()).stream().filter(t -> run.task() == null || run.task().allowedTools().contains(t.name())).toList(), remaining, finalizing,
                        finalizing ? finalizeDirective : "", run.preparedRequest(), run.task(), run.memory())));
    }
    private Decision decide(RunState run, ModelContext context) {
        return harness.stage(run, context.finalizing() ? "agent.model.finalize" : "agent.model", HookPoint.PRE_MODEL, HookPoint.POST_MODEL,
                Map.of("run.finalizing", context.finalizing(), "loop.step", run.snapshot().steps(),
                        "agent.available_tools", context.availableTools().stream().map(ToolDefinition::name).limit(32).toList()), () -> {
            TraceDetails.capture("input", context, "MODEL_CONTEXT");
            TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(), Map.of("model.name", model.modelName() == null ? "unspecified" : model.modelName(),
                    "model.streaming", false, "model.first_token_state", "NOT_APPLICABLE"));
            Decision raw = harness.modelCall(run, context.finalizing(), control -> context.finalizing()
                    ? model.finalizeWithoutTools(context, control) : model.decide(context, control));
            if (raw == null || raw.kind() == null) throw new AgentException("INVALID_DECISION", "Missing model decision");
            return harness.transform(run, TransformPoint.MODEL_RESULT, raw);
        });
    }
    private boolean iteration(RunState run, String message, int remaining, Progress progress) {
        ModelContext context = context(run, message, remaining, false);
        Decision decision = decide(run, context);
        run.checkpoint();
        return switch (decision.kind()) {
            case TOOL -> executeTool(run, context, decision.call(), progress);
            case CLARIFY -> { delivery.clarification(run, decision.clarification(), delivery.reserve(run)); yield true; }
            case ANSWER -> { delivery.answer(run, decision.answer(), delivery.reserve(run), StopReason.ANSWER); yield true; }
        };
    }
    private boolean executeTool(RunState run, ModelContext context, ToolCall call, Progress progress) {
        if (call == null) throw new AgentException("INVALID_DECISION", "Missing tool call");
        if (context.availableTools().stream().noneMatch(tool -> tool.name().equals(call.name())))
            throw new AgentException("TOOL_NOT_AVAILABLE", "Tool is not exposed in the current model context");
        String name = call.name();
        run.emit("PROGRESS", Map.of("stage", "tool", "tool", name));
        ToolResult result;
        try {
            int attempt = name.equals(progress.failingTool) ? progress.consecutiveFailures + 1 : 1;
            result = harness.stage(run, "agent.tool", HookPoint.PRE_TOOL, HookPoint.POST_TOOL,
                    Map.of("tool.name", name, "execution.attempt", attempt, "execution.retry_count", attempt - 1), () -> {
                TraceDetails.input(call);
                ToolCall actual = harness.transform(run, TransformPoint.TOOL_CALL, call);
                if (progress.successfulCalls.contains(actual)) throw new AgentException("NO_PROGRESS", "Repeated successful tool call");
                ToolResult raw = tools.execute(actual, run);
                ToolResult admitted = harness.transform(run, TransformPoint.TOOL_RESULT, raw);
                progress.success(actual);
                return admitted;
            });
        } catch (AgentException e) {
            run.checkpoint();
            if (!RETRYABLE.contains(e.code())) throw e;
            int attempt = progress.failure(name);
            if (attempt >= harness.policy().consecutiveToolFailures())
                throw new AgentException("TOOL_FAILURE_LIMIT", "Consecutive read-only tool failures reached the limit");
            result = harness.transform(run, TransformPoint.TOOL_RESULT, new ToolResult(name,
                    Map.of("error_code", e.code(), "retryable", true, "attempt", attempt), null));
            run.execution().observe(result);
            run.emit("PROGRESS", Map.of("stage", "tool_failed", "tool", name, "code", e.code(), "attempt", attempt));
            return false;
        }
        run.execution().observe(result);
        run.emit("PROGRESS", Map.of("stage", "tool_completed", "tool", name));
        if (result.terminalAnswer() != null) {
            delivery.answer(run, result.terminalAnswer(), delivery.reserve(run), StopReason.TOOL_DELIVERY);
            return true;
        }
        return false;
    }
    private void finalizeWithoutTools(RunState run, String message) {
        TurnControl.Reservation reservation = delivery.reserve(run);
        harness.stage(run, "agent.finalize", null, null, Map.of("run.finalizing", true), () -> {
            run.emit("PROGRESS", Map.of("stage", "finalizing"));
            ModelContext context = context(run, message, 0, true);
            Decision decision;
            try {
                decision = decide(run, context);
                if (decision.kind() == Kind.TOOL) throw new AgentException("FINALIZATION_TOOL_CALL", "Tools are disabled during finalization");
            } catch (AgentException e) {
                run.checkpoint();
                if (!Set.of("MODEL_CALL_TIMEOUT", "MODEL_ERROR", "INVALID_DECISION", "FINALIZATION_TOOL_CALL").contains(e.code())) throw e;
                delivery.answer(run, EvidenceFallbackRenderer.render(run), reservation, StopReason.FINALIZED_ANSWER);
                return true;
            }
            if (decision.kind() == Kind.CLARIFY) delivery.clarification(run, decision.clarification(), reservation);
            else delivery.answer(run, decision.answer(), reservation, StopReason.FINALIZED_ANSWER);
            return true;
        });
    }
}
