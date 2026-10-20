package com.dokitv.agent.harness.trace;

/** Called only by the export worker. Success means destination ACK; close must be idempotent. */
public interface TraceSink extends AutoCloseable {
    void send(TraceEnvelope envelope, String json) throws Exception;
    @Override default void close() throws Exception {}
}
