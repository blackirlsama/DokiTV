CREATE TABLE IF NOT EXISTS user (
  user_id BIGINT PRIMARY KEY, state VARCHAR(32) NOT NULL, role VARCHAR(32) NOT NULL DEFAULT 'user', is_delete INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS video (
  video_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, status INT NOT NULL DEFAULT 1, is_delete INT NOT NULL DEFAULT 0,
  INDEX idx_video_owner(user_id,is_delete)
);
CREATE TABLE IF NOT EXISTS bullet (
  bullet_id BIGINT PRIMARY KEY, video_id BIGINT NOT NULL, user_id BIGINT NOT NULL, content VARCHAR(255) NOT NULL,
  color VARCHAR(16), playback_time DOUBLE NOT NULL, create_time TIMESTAMP(3) NOT NULL,
  INDEX idx_bullet_video_created(video_id,create_time)
);
CREATE TABLE IF NOT EXISTS video_stats (
  video_id BIGINT PRIMARY KEY, view_count INT NOT NULL DEFAULT 0, bullet_count INT NOT NULL DEFAULT 0,
  like_count INT NOT NULL DEFAULT 0, coin_count INT NOT NULL DEFAULT 0, favorite_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, is_delete INT NOT NULL DEFAULT 0
);
