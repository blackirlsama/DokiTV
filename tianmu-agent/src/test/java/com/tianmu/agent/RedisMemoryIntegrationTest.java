package com.tianmu.agent;

import com.tianmu.agent.adapters.storage.RedisMemoryCache;
import com.tianmu.agent.core.MemoryTypes.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="AGENT_STORAGE_IT", matches="true")
class RedisMemoryIntegrationTest {
    RedisMemoryCache cache(int port) { return new RedisMemoryCache(new ObjectMapper().findAndRegisterModules(), true, System.getenv("AGENT_REDIS_HOST"), port, ""); }
    @Test void realRedisPreservesLatestVersionAndReturnsTheDecodedWindow() {
        try (var cache = cache(Integer.parseInt(System.getenv("AGENT_REDIS_PORT")))) {
            String key = RedisMemoryCache.key("it", "alice", UUID.randomUUID());
            var msg = new Message(UUID.randomUUID(), UUID.randomUUID(), 1, "USER", "newest", Instant.now());
            try {
                assertTrue(cache.put(key, new RedisMemoryCache.Window(2, 1, Instant.now().plusSeconds(30), List.of(msg))));
                assertTrue(cache.put(key, new RedisMemoryCache.Window(1, 1, Instant.now().plusSeconds(30), List.of())));
                var view = cache.get(key, 2, 1); assertNotNull(view); assertEquals("newest", view.messages().get(0).text());
                assertNull(cache.get(key, 1, 1)); assertEquals(1, cache.hits.get());
            } finally { cache.evict(key); }
        }
    }
    @Test void unavailableRedisIsABoundedCacheMiss() {
        try (var cache = cache(16380)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertNull(cache.get("it:missing", 1, 1)));
            assertTrue(cache.failures.get() > 0);
        }
    }
}
