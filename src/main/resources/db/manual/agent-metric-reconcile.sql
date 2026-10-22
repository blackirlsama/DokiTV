-- Read-only reference queries. Bind parameters in your SQL client; use an authenticated test account's IDs.
-- For TIMESTAMP columns bind start/end as UTC instants using a UTC JDBC Calendar/session.
-- For DATETIME bind wall-clock bounds converted to the configured storage time zone.
SELECT COUNT(*) AS retained_daily_count
FROM bullet b JOIN video v ON v.video_id=b.video_id
WHERE v.video_id=? AND v.user_id=? AND v.is_delete=0
  AND b.create_time>=? AND b.create_time<?;

-- Compare cumulative counter with all retained rows, NOT with a single day's count.
SELECT v.video_id,s.bullet_count AS stored_cumulative_count,COUNT(b.bullet_id) AS retained_total,
       COALESCE(s.bullet_count,0)-COUNT(b.bullet_id) AS difference
FROM video v LEFT JOIN video_stats s ON s.video_id=v.video_id
LEFT JOIN bullet b ON b.video_id=v.video_id
WHERE v.video_id=? AND v.user_id=? AND v.is_delete=0
GROUP BY v.video_id,s.bullet_count;
