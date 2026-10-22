-- Review existing indexes first. Run explicitly on the business database during deployment.
-- This file is not an Agent Flyway migration and is never applied by Agent startup.
CREATE INDEX idx_bullet_video_created ON bullet(video_id, create_time);
