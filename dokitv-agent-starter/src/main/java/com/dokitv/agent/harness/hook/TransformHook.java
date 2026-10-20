package com.dokitv.agent.harness.hook;

/** Register a Spring bean. The output of one hook is the next hook's input at the same typed point. */
public interface TransformHook<T> {
    String name();
    TransformPoint<T> point();
    default int order() { return 0; }
    T transform(T input, TransformContext context);
}
