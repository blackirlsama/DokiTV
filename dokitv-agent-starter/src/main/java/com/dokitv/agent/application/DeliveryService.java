package com.dokitv.agent.application;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.StopReason;
import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.core.PreparationTypes.FaqAnswer;
import com.dokitv.agent.harness.RuntimeHarness;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.runtime.RunState;
import com.dokitv.agent.runtime.control.TurnControl;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DeliveryService {
    private final RuntimeHarness harness;
    public DeliveryService(RuntimeHarness harness) { this.harness = harness; }
    public void clarification(RunState run, String question) { clarification(run, question, reserve(run)); }
    public TurnControl.Reservation reserve(RunState run) {
        run.checkpoint();
        TurnControl.Reservation reservation = run.reserveFinalResponse();
        if (reservation == null) { run.checkpoint(); throw new AgentException("FINAL_RESPONSE_BUSY", "Final response already reserved"); }
        return reservation;
    }
    public void faq(RunState run, FaqAnswer faq) {
        TurnControl.Reservation reservation = reserve(run);
        harness.stage(run, "agent.deliver.faq", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER, Map.of(), () -> {
            Answer answer = harness.transform(run, TransformPoint.ANSWER, new Answer(faq.text(), List.of()));
            // Only the trusted FAQ route may deliver a static answer without metric evidence.
            if (!answer.evidenceIds().isEmpty()) throw new AgentException("INVALID_EVIDENCE", "FAQ cannot invent evidence");
            run.checkpoint();
            run.succeed(reservation, answer, StopReason.FAQ_ANSWER);
            return true;
        });
    }
    public void clarification(RunState run, String text, TurnControl.Reservation reservation) {
        harness.stage(run, "agent.deliver", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER, Map.of(), () -> {
            String clarification = harness.transform(run, TransformPoint.CLARIFICATION, text);
            run.checkpoint();
            run.clarify(reservation, clarification);
            return true;
        });
    }
    public void answer(RunState run, Answer raw, TurnControl.Reservation reservation, StopReason reason) {
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
            Evidence evidence = run.execution().evidence().get(id);
            if (evidence == null || !evidence.owner().equals(run.owner) || !evidence.runId().equals(run.id))
                throw new AgentException("INVALID_EVIDENCE", "Answer cites unavailable evidence");
        }
    }

}
