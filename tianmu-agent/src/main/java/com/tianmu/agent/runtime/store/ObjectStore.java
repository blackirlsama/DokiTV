package com.tianmu.agent.runtime.store;

/** Private immutable objects; implementations enforce bounded reads and verify content integrity. */
public interface ObjectStore {
    /** Adapters should check that their configured destination is usable without exposing its location. */
    default void verifyReady() {}
    void put(String objectKey, byte[] body);
    byte[] get(String objectKey, long expectedSize, String expectedSha256);
    void delete(String objectKey);
}
