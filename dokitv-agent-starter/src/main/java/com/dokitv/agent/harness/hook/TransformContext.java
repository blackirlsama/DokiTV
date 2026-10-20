package com.dokitv.agent.harness.hook;

import java.util.UUID;

/** Metadata only; the transform's typed value is the only content it may replace. */
public record TransformContext(String schemaVersion, UUID runId, String traceId, int step) {}
