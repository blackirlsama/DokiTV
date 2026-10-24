package com.tianmu.agent.harness.trace;

/** Stable business taxonomy, independent of legacy span display names. */
public enum SpanType {
    RECEIVE_QUERY, PRE_ROUTE, FAQ_LOOKUP, FAQ_DIRECT, EMOTION_RECOGNITION, QUERY_UNDERSTANDING,
    MEMORY_LOAD, CONTEXT_BUILD, AGENT_START, LLM_INFERENCE, TOOL_CALL, READ_SKILL,
    ANSWER_READY, TERMINAL_DELIVERY, AGENT_END, FINISH, ERROR;

    public String value() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static SpanType forStage(String name) {
        return switch (name) {
            case "agent.preparation" -> PRE_ROUTE;
            case "agent.prepare.faq" -> FAQ_LOOKUP;
            case "agent.deliver.faq" -> FAQ_DIRECT;
            case "agent.prepare.emotion" -> EMOTION_RECOGNITION;
            case "agent.prepare.understanding" -> QUERY_UNDERSTANDING;
            case "agent.memory.load" -> MEMORY_LOAD;
            case "agent.context", "agent.assemble" -> CONTEXT_BUILD;
            case "agent.model", "agent.model.finalize" -> LLM_INFERENCE;
            case "agent.tool" -> TOOL_CALL;
            case "agent.skill" -> READ_SKILL;
            case "agent.deliver" -> ANSWER_READY;
            default -> null;
        };
    }
}
