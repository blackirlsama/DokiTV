package com.tianmu.agent.harness.hook;

import com.tianmu.agent.core.AgentTypes.*;

/** Closed, typed insertion points; add plug-ins freely, add new execution semantics by versioning the core. */
public final class TransformPoint<T> {
    public static final TransformPoint<String> INPUT = new TransformPoint<>("input", String.class);
    public static final TransformPoint<ModelContext> CONTEXT = new TransformPoint<>("context", ModelContext.class);
    public static final TransformPoint<Decision> MODEL_RESULT = new TransformPoint<>("model_result", Decision.class);
    public static final TransformPoint<ToolCall> TOOL_CALL = new TransformPoint<>("tool_call", ToolCall.class);
    public static final TransformPoint<ToolResult> TOOL_RESULT = new TransformPoint<>("tool_result", ToolResult.class);
    public static final TransformPoint<Answer> ANSWER = new TransformPoint<>("answer", Answer.class);
    public static final TransformPoint<String> CLARIFICATION = new TransformPoint<>("clarification", String.class);
    private final String name;
    private final Class<T> type;
    private TransformPoint(String name, Class<T> type) { this.name = name; this.type = type; }
    public String name() { return name; }
    public Class<T> type() { return type; }
}
