package com.dokitv.agent.runtime;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.model.ModelPort;
import com.dokitv.agent.tool.ToolDispatcher;
import com.dokitv.agent.harness.RuntimeHarness;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.runtime.control.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final Set<String> RETRYABLE = Set.of("TOOL_CALL_TIMEOUT", "TOOL_TEMPORARY_ERROR");
    private static final String FINALIZE_DIRECTIVE = "Tool budget is exhausted. Use only existing evidence to answer the original question. "
            + "Do not call tools. Return an ANSWER with existing evidence IDs, or CLARIFY if evidence is insufficient. "
            + "State limitations and do not invent facts or causal explanations.";
    private final ModelPort model;
    private final ToolDispatcher tools;
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
        this.model = model; this.tools = tools; this.harness = harness;
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
        String message = harness.stage(run, "agent.input", null, null, Map.of(),
                () -> harness.transform(run, TransformPoint.INPUT, run.message));
        harness.stage(run, "agent.route", HookPoint.PRE_ROUTE, null, Map.of(), () -> true);
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
        if (!harness.policy().finalizeEnabled() || run.evidence.isEmpty()) { run.fail("STEP_LIMIT"); return; }
        finalizeWithoutTools(run, message);
    }
    private ModelContext context(RunState run, String message, int remaining, boolean finalizing) {
        return harness.stage(run, "agent.context", null, null, Map.of("context.observation_count", run.observations.size()),
                () -> harness.transform(run, TransformPoint.CONTEXT, new ModelContext(message,
                        LocalDate.now(ZoneId.of("Asia/Shanghai")), run.owner, run.activeSkills, run.observations,
                        finalizing ? List.of() : tools.definitions(run.activeSkills), remaining, finalizing,
                        finalizing ? FINALIZE_DIRECTIVE : "")));
    }
    private Decision decide(RunState run, ModelContext context) {
        return harness.stage(run, context.finalizing() ? "agent.model.finalize" : "agent.model", HookPoint.PRE_MODEL, HookPoint.POST_MODEL,
                Map.of("run.finalizing", context.finalizing()), () -> {
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
            case CLARIFY -> { deliverClarification(run, decision.clarification(), reserve(run)); yield true; }
            case ANSWER -> { deliverAnswer(run, decision.answer(), reserve(run), StopReason.ANSWER); yield true; }
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
            result = harness.stage(run, "agent.tool", HookPoint.PRE_TOOL, HookPoint.POST_TOOL, Map.of("tool.name", name), () -> {
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
            run.observations.add(result);
            run.emit("PROGRESS", Map.of("stage", "tool_failed", "tool", name, "code", e.code(), "attempt", attempt));
            return false;
        }
        run.observations.add(result);
        run.emit("PROGRESS", Map.of("stage", "tool_completed", "tool", name));
        if (result.terminalAnswer() != null) {
            deliverAnswer(run, result.terminalAnswer(), reserve(run), StopReason.TOOL_DELIVERY);
            return true;
        }
        return false;
    }
    private void finalizeWithoutTools(RunState run, String message) {
        TurnControl.Reservation reservation = reserve(run);
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
                deliverAnswer(run, EvidenceFallbackRenderer.render(run), reservation, StopReason.FINALIZED_ANSWER);
                return true;
            }
            if (decision.kind() == Kind.CLARIFY) deliverClarification(run, decision.clarification(), reservation);
            else deliverAnswer(run, decision.answer(), reservation, StopReason.FINALIZED_ANSWER);
            return true;
        });
    }
    private TurnControl.Reservation reserve(RunState run) {
        run.checkpoint();
        TurnControl.Reservation reservation = run.reserveFinalResponse();
        if (reservation == null) { run.checkpoint(); throw new AgentException("FINAL_RESPONSE_BUSY", "Final response already reserved"); }
        return reservation;
    }
    private void deliverClarification(RunState run, String text, TurnControl.Reservation reservation) {
        harness.stage(run, "agent.deliver", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER, Map.of(), () -> {
            String clarification = harness.transform(run, TransformPoint.CLARIFICATION, text);
            run.checkpoint();
            run.clarify(reservation, clarification);
            return true;
        });
    }
    private void deliverAnswer(RunState run, Answer raw, TurnControl.Reservation reservation, StopReason reason) {
        harness.stage(run, "agent.deliver", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER,
                Map.of("answer.evidence_count", raw == null ? 0 : raw.evidenceIds().size()), () -> {
            Answer answer = harness.transform(run, TransformPoint.ANSWER, raw);
            if (reason == StopReason.FINALIZED_ANSWER) answer = new Answer("【轮次已达上限，仅基于已获取证据收尾】\n" + answer.text(), answer.evidenceIds());
            validateAnswer(answer, run);
            run.checkpoint();
            run.succeed(reservation, answer, reason);
            return true;
        });
    }
    private void validateAnswer(Answer answer, RunState run) {
        if (answer == null || answer.text() == null || answer.text().isBlank() || answer.text().length() > 8000
                || answer.evidenceIds().isEmpty() || answer.evidenceIds().size() > 10)
            throw new AgentException("INVALID_ANSWER", "A bounded answer with evidence is required");
        for (String id : answer.evidenceIds()) {
            Evidence evidence = run.evidence.get(id);
            if (evidence == null || !evidence.owner().equals(run.owner) || !evidence.runId().equals(run.id))
                throw new AgentException("INVALID_EVIDENCE", "Answer cites unavailable evidence");
        }
    }
}
