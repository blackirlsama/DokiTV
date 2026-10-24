-- Isolated demo business fixtures in the Agent-only database. Never used by business profile.
CREATE TABLE demo_video_metric (
    tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    video_id BIGINT UNSIGNED NOT NULL,
    metric_date DATE NOT NULL,
    metric_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    metric_value BIGINT UNSIGNED NOT NULL,
    metric_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_as_of DATETIME(6) NOT NULL,
    timezone_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    is_complete BOOLEAN NOT NULL,
    is_demo BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, video_id, metric_date, metric_name),
    CONSTRAINT chk_demo_video_metric_demo CHECK (is_demo = TRUE)
) ENGINE=InnoDB;

INSERT INTO demo_video_metric
    (tenant_id, video_id, metric_date, metric_name, metric_value, metric_version,
     source, data_as_of, timezone_name, is_complete, is_demo)
VALUES
    ('demo', 1001, '2026-10-23', 'bullet_count', 128, 'mysql-demo-v1', 'mysql-demo-fixture', '2026-10-24 00:00:00.000000', 'Asia/Shanghai', TRUE, TRUE),
    ('demo', 1002, '2026-10-23', 'bullet_count', 76,  'mysql-demo-v1', 'mysql-demo-fixture', '2026-10-24 00:00:00.000000', 'Asia/Shanghai', TRUE, TRUE),
    ('demo', 1001, '2026-10-22', 'bullet_count', 121, 'mysql-demo-v1', 'mysql-demo-fixture', '2026-10-23 00:00:00.000000', 'Asia/Shanghai', TRUE, TRUE),
    ('demo', 1002, '2026-10-22', 'bullet_count', 83,  'mysql-demo-v1', 'mysql-demo-fixture', '2026-10-23 00:00:00.000000', 'Asia/Shanghai', TRUE, TRUE);
