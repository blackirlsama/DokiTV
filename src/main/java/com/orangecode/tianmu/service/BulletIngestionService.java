package com.orangecode.tianmu.service;

import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Stable event IDs and a per-video database lock make insertion and counter increment idempotent. */
@Service
public class BulletIngestionService {
    private final JdbcTemplate db;
    private final ZoneId datetimeZone;
    public BulletIngestionService(JdbcTemplate db) { this(db,"Asia/Shanghai"); }
    @Autowired public BulletIngestionService(JdbcTemplate db,
            @Value("${agent.bridge.datetime-storage-zone:Asia/Shanghai}") String zone) { this.db = db; this.datetimeZone = ZoneId.of(zone); }
    @Transactional(rollbackFor=Exception.class)
    public void persist(SendBulletRequest event) {
        if (event == null || event.getBulletId() == null || event.getBulletId() <= 0 || event.getVideoId() == null || event.getUserId() == null
                || event.getContent() == null || event.getContent().isBlank() || event.getPlaybackTime() == null || !Double.isFinite(event.getPlaybackTime()) || event.getPlaybackTime() < 0)
            throw new IllegalArgumentException("Invalid bullet event");
        var video = db.queryForList("SELECT video_id FROM video WHERE video_id=? AND is_delete=0 FOR UPDATE", event.getVideoId());
        if (video.isEmpty() || db.queryForObject("SELECT COUNT(*) FROM user WHERE user_id=? AND is_delete=0", Long.class, event.getUserId()) != 1)
            throw new IllegalArgumentException("Bullet user or video unavailable");
        var previous = db.queryForList("SELECT video_id,user_id,content,playback_time,color FROM bullet WHERE bullet_id=?", event.getBulletId());
        if (!previous.isEmpty()) {
            var saved = previous.get(0);
            if (((Number)saved.get("video_id")).longValue() != event.getVideoId()
                    || ((Number)saved.get("user_id")).longValue() != event.getUserId()
                    || !Objects.equals(saved.get("content"), event.getContent())
                    || Double.compare(((Number)saved.get("playback_time")).doubleValue(), event.getPlaybackTime()) != 0
                    || !Objects.equals(saved.get("color"), event.getColor()))
                throw new IllegalArgumentException("Bullet event ID conflicts with existing payload");
            return;
        }
        Date created = event.getCreateTime();
        if (created == null) {
            // Legacy queued messages have no event timestamp. Their creation date is the ingestion date.
            created = new Date();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("legacy_bullet_timestamp_missing bullet_id={}", event.getBulletId());
        }
        String type = db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bullet' AND COLUMN_NAME='create_time'", String.class);
        if (!Set.of("timestamp","datetime").contains(type)) throw new IllegalStateException("Unsupported bullet timestamp column");
        Object timestamp = "timestamp".equals(type) ? java.math.BigDecimal.valueOf(created.getTime(),3)
                : LocalDateTime.ofInstant(created.toInstant(),datetimeZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,color,playback_time,create_time) VALUES(?,?,?,?,?,?," + ("timestamp".equals(type) ? "FROM_UNIXTIME(?)" : "?") + ")",
                event.getBulletId(), event.getVideoId(), event.getUserId(), event.getContent(), event.getColor(), event.getPlaybackTime(), timestamp);
        db.update("INSERT INTO video_stats(video_id,view_count,bullet_count,like_count,coin_count,favorite_count,comment_count,create_time,update_time,is_delete) VALUES(?,0,0,0,0,0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0) ON DUPLICATE KEY UPDATE video_id=video_id", event.getVideoId());
        if (db.update("UPDATE video_stats SET bullet_count=COALESCE(bullet_count,0)+1,update_time=UTC_TIMESTAMP() WHERE video_id=?", event.getVideoId()) != 1)
            throw new IllegalStateException("Bullet counter update failed");
    }
}
