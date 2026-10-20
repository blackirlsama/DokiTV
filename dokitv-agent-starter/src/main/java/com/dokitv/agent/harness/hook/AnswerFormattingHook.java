package com.dokitv.agent.harness.hook;

import com.dokitv.agent.core.AgentTypes.Answer;
import org.springframework.stereotype.Component;

@Component
public class AnswerFormattingHook implements TransformHook<Answer> {
    public String name() { return "format-answer"; }
    public TransformPoint<Answer> point() { return TransformPoint.ANSWER; }
    public Answer transform(Answer input, TransformContext context) { return new Answer(input.text().strip(), input.evidenceIds()); }
}
