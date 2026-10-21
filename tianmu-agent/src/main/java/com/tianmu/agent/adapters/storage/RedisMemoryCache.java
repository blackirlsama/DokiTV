package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.MemoryTypes.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Cache errors only affect this derived view, never the authoritative database commit. */
public final class RedisMemoryCache implements AutoCloseable {
    public record Window(long version, long beforeTurn, Instant validUntil, List<Message> messages) {
        public Window { messages = List.copyOf(messages); }
    }
    private final ObjectMapper mapper; private final RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private Instant retryAfter = Instant.MIN;
    public final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), failures = new AtomicLong();
    public RedisMemoryCache(ObjectMapper mapper, boolean enabled, String host, int port, String password) {
        this.mapper = mapper;
        if (!enabled) { client = null; return; }
        RedisURI.Builder uri = RedisURI.builder().withHost(host).withPort(port).withTimeout(Duration.ofMillis(300));
        if (!password.isEmpty()) uri.withPassword(password.toCharArray());
        client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder().autoReconnect(false)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build()).build());
    }
    private synchronized StatefulRedisConnection<String, String> connect() {
        if (client == null || Instant.now().isBefore(retryAfter)) return null;
        if (connection == null || !connection.isOpen()) connection = client.connect();
        return connection;
    }
    private synchronized void unavailable() {
        failures.incrementAndGet(); retryAfter = Instant.now().plusSeconds(2);
        if (connection != null) connection.close(); connection = null;
    }
    public Window get(String key, long version, long before) {
        try {
            var c = connect(); if (c == null) { misses.incrementAndGet(); return null; }
            String body = c.sync().get(key);
            if (body != null && body.getBytes(StandardCharsets.UTF_8).length <= 262144) {
                if (body.length() < 21 || body.charAt(20) != '\n') return null;
                Window value = mapper.readValue(body.substring(21), Window.class);
                if (value.version() == version && value.beforeTurn() == before && Instant.now().isBefore(value.validUntil())) { hits.incrementAndGet(); return value; }
            }
        } catch (Exception e) { unavailable(); }
        misses.incrementAndGet(); return null;
    }
    public boolean put(String key, Window window) {
        try {
            var c = connect(); if (c == null) return client == null;
            List<Message> selected = new ArrayList<>(window.messages()); String body;
            do {
                body = mapper.writeValueAsString(new Window(window.version(), window.beforeTurn(), window.validUntil(), selected));
                if (body.getBytes(StandardCharsets.UTF_8).length <= 262123) break;
                if (selected.isEmpty()) return false;
                selected.remove(0); if (!selected.isEmpty()) selected.remove(0);
            } while (true);
            long ttl = Math.min(86400, Duration.between(Instant.now(), window.validUntil()).toSeconds());
            if (ttl <= 0) return true;
            // Fixed-width decimal avoids Lua floating point precision loss for version comparison.
            String version = String.format(Locale.ROOT, "%020d", window.version());
            String value = version + "\n" + body;
            String script = "local v=redis.call('GET',KEYS[1]); if v and string.sub(v,1,20)>ARGV[1] then return 0 end; redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); return 1";
            c.sync().eval(script, ScriptOutputType.INTEGER, new String[]{key}, version, value, Long.toString(ttl)); return true;
        } catch (Exception e) { unavailable(); return false; }
    }
    public static String key(String tenant, String owner, UUID session) {
        try {
            String source = tenant.length() + ":" + tenant + owner.length() + ":" + owner + session;
            return "tianmu:memory:{" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))) + "}:recent";
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public void evict(String key) {
        try { var c = connect(); if (c != null) c.sync().del(key); }
        catch (Exception e) { unavailable(); }
    }
    @Override public synchronized void close() { if (connection != null) connection.close(); if (client != null) client.shutdown(); }
}
