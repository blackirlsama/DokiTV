package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.utils.JwtUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shares the business application's JWT and online-session rules; never trusts client user IDs. */
public final class BusinessAuthentication {
    public record Principal(String tenantId, String userId) {}
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final BridgeProperties config;
    public BusinessAuthentication(JdbcTemplate db, StringRedisTemplate redis, BridgeProperties config) {
        this.db = new JdbcTemplate(java.util.Objects.requireNonNull(db.getDataSource()));
        this.db.setQueryTimeout(config.queryTimeoutSeconds()); this.redis = redis; this.config = config;
    }
    public Principal authenticate(String header) {
        try {
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
            if (token == null || token.isBlank() || token.length() > 8192) throw new BridgeException("UNAUTHORIZED", 401);
            var claims = JwtUtil.parse(token);
            if (claims == null || claims.getSubject() == null || !claims.getSubject().matches("[1-9][0-9]{0,18}"))
                throw new BridgeException("UNAUTHORIZED", 401);
            String user = claims.getSubject(), online = redis.opsForValue().get(user);
            if (online == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), online.getBytes(StandardCharsets.UTF_8)))
                throw new BridgeException("UNAUTHORIZED", 401);
            var rows = db.queryForList("SELECT state FROM user WHERE user_id=? AND is_delete=0", Long.parseLong(user));
            if (rows.size() != 1 || !config.activeUserStates().contains(String.valueOf(rows.get(0).get("state"))))
                throw new BridgeException("UNAUTHORIZED", 401);
            return new Principal(config.tenantId(), user);
        } catch (BridgeException e) { throw e; }
        catch (NumberFormatException e) { throw new BridgeException("UNAUTHORIZED", 401); }
        catch (Exception e) { throw new BridgeException("BUSINESS_UNAVAILABLE", 503); }
    }
}
