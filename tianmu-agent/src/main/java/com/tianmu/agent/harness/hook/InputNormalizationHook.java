package com.tianmu.agent.harness.hook;

import org.springframework.stereotype.Component;

@Component
public class InputNormalizationHook implements TransformHook<String> {
    public String name() { return "normalize-input"; }
    public TransformPoint<String> point() { return TransformPoint.INPUT; }
    public int order() { return -100; }
    public String transform(String input, TransformContext context) { return input.strip(); }
}
