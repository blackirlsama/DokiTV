package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed SQL only. Exact aggregates and bounded, explicitly biased excerpts share a read snapshot. */
public final class BusinessDanmakuAnalysis {
    public record Query(long videoId, LocalDateTime start, LocalDateTime end) {}
    public record Bucket(int index, long count) {}
    public record Sample(String id, int bucket, String text, boolean truncated) {}
    public record Dataset(Query query, long totalMessages, long uniqueSenders, List<Bucket> buckets,
                          List<Sample> samples, Instant dataAsOf, String source, boolean demo) {}
    private record Totals(long messages, long senders, Instant asOf) {}
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BridgeProperties config;
    public BusinessDanmakuAnalysis(JdbcTemplate source, BridgeProperties config) {
        this.config = config; db = new JdbcTemplate(Objects.requireNonNull(source.getDataSource())); db.setQueryTimeout(config.queryTimeoutSeconds());
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setTimeout(config.queryTimeoutSeconds());
    }
    public Dataset query(BusinessAuthentication.Principal principal, long videoId, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (videoId <= 0 || start == null || end == null || !start.isBefore(end) || start.getNano() != 0 || end.getNano() != 0
                || Duration.between(start, end).compareTo(Duration.ofDays(1)) > 0 || end.isAfter(now)
                || start.toLocalDate().isBefore(now.toLocalDate().minusDays(30))) throw new BridgeException("INVALID_ANALYSIS_RANGE", 400);
        return tx.execute(status -> {
            // This ownership convention is shared with existing metric authorization.
            Integer allowed = db.queryForObject("SELECT COUNT(*) FROM video WHERE video_id=? AND user_id=? AND is_delete=0", Integer.class, videoId, Long.parseLong(principal.userId()));
            if (allowed == null || allowed != 1) throw new BridgeException("FORBIDDEN", 403);
            String type = db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bullet' AND COLUMN_NAME='create_time'", String.class);
            if (!"timestamp".equals(type) && !"datetime".equals(type)) throw new BridgeException("UNSUPPORTED_TIME_COLUMN", 503);
            ZoneId storage = "timestamp".equals(type) ? ZoneOffset.UTC : ZoneId.of(config.datetimeStorageZone());
            String from = wall(start, storage), to = wall(end, storage);
            long seconds = Duration.between(start, end).getSeconds(), width = Math.max(1, (seconds + 11) / 12);
            String scope = " FROM bullet WHERE video_id=? AND create_time>=? AND create_time<?";
            var totals = db.queryForObject("SELECT COUNT(*) AS total,COUNT(DISTINCT user_id) AS senders,UTC_TIMESTAMP(6) AS as_of" + scope,
                    (rs, n) -> new Totals(rs.getLong("total"), rs.getLong("senders"), rs.getTimestamp("as_of", Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()), videoId, from, to);
            List<Bucket> buckets = new ArrayList<>(); long[] counts = new long[12];
            db.query("SELECT FLOOR(TIMESTAMPDIFF(SECOND,?,create_time)/?) AS bin,COUNT(*) AS n" + scope + " GROUP BY bin", rs -> {
                int bin = rs.getInt("bin"); if (bin < 0 || bin >= 12) throw new BridgeException("INVALID_ANALYSIS_DATA", 503);
                counts[bin] = rs.getLong("n");
            }, from, width, videoId, from, to);
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                buckets.add(new Bucket(i, counts[i])); if (counts[i] == 0) continue;
                int bin = i;
                LocalDateTime left = start.plusSeconds(width * i), right = start.plusSeconds(Math.min(seconds, width * (i + 1)));
                samples.addAll(db.query("SELECT bullet_id,LEFT(COALESCE(content,''),160) AS excerpt,CHAR_LENGTH(COALESCE(content,''))>160 AS clipped" + scope + " ORDER BY create_time,bullet_id LIMIT 4",
                        (rs, n) -> new Sample(Long.toString(rs.getLong("bullet_id")), bin, redact(rs.getString("excerpt")), rs.getBoolean("clipped")), videoId, wall(left, storage), wall(right, storage)));
            }
            return new Dataset(new Query(videoId, start, end), totals.messages(), totals.senders(),
                    List.copyOf(buckets), List.copyOf(samples), totals.asOf(), "mysql:bullet:danmaku-retained-v1", false);
        });
    }
    private static String wall(LocalDateTime time, ZoneId storage) { return time.atZone(ZONE).withZoneSameInstant(storage).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
    private static String redact(String text) {
        return text.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[email]").replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[phone]");
    }
}
