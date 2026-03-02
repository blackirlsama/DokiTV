package com.orangecode.tianmu.client;


import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.orangecode.tianmu.constants.VideoConstant;
import com.orangecode.tianmu.esdao.UserEsDao;
import com.orangecode.tianmu.esdao.VideoEsDao;
import com.orangecode.tianmu.model.es.UserEs;
import com.orangecode.tianmu.model.es.VideoEs;

import cn.hutool.core.date.DateUtil;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class CanalClient implements CommandLineRunner {

    @Resource
    private CanalConnector canalConnector;

    @Resource
    private UserEsDao userEsDao;

    @Resource
    private VideoEsDao videoEsDao;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    private static final int MAX_RETRY_TIMES = 5; // 发生错误后最大重试次数

    private static final long INITIAL_RETRY_DELAY = 1000; // 初始重试延迟1秒

    private static final long MAX_RETRY_DELAY = 60000; // 最大重试延迟60秒

    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒发送一次心跳

    private static final long IDLE_CHECK_INTERVAL = 5000; // 5秒检查一次空闲状态
    // 需要监听的表名集合
    private static final Set<String> MONITOR_TABLES = Set.of("user", "video", "video_stats", "user_stats", "bullet");

    @Override
    public void run(String... args) {
        new Thread(this::process).start();
    }


    private void process() {
        int batchSize = 1000;
        int retryTimes = 0;
        long retryDelay = INITIAL_RETRY_DELAY;
        long lastActiveTime = System.currentTimeMillis();

        while (true) {
            try {
                // 检查连接状态
                if (!canalConnector.checkValid()) {
                    reconnectCanal();
                    retryTimes = 0;
                    retryDelay = INITIAL_RETRY_DELAY;
                    lastActiveTime = System.currentTimeMillis();
                }

                // 检查是否需要发送心跳
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastActiveTime > HEARTBEAT_INTERVAL) {
                    sendHeartbeat();
                    lastActiveTime = currentTime;
                    continue;
                }

                Message message = canalConnector.getWithoutAck(batchSize);
                long batchId = message.getId();
                int size = message.getEntries().size();

                if (batchId == -1 || size == 0) {
                    // 没有数据时短暂休眠，避免CPU空转
                    Thread.sleep(IDLE_CHECK_INTERVAL);
                    continue;
                }

                lastActiveTime = System.currentTimeMillis(); // 更新最后活跃时间

                try {
                    handleMessage(message.getEntries());
                    canalConnector.ack(batchId);
                    retryTimes = 0;
                    retryDelay = INITIAL_RETRY_DELAY;
                } catch (Exception e) {
                    log.error("处理消息内容出错，尝试回滚", e);
                    safeRollback(batchId);
                    throw e;
                }

            } catch (Exception e) {
                log.error("处理canal消息出错", e);

                if (retryTimes++ >= MAX_RETRY_TIMES) {
                    log.error("达到最大重试次数{}，等待后重新尝试", MAX_RETRY_TIMES);
                    retryTimes = 0;
                    try {
                        Thread.sleep(MAX_RETRY_DELAY);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }

                long sleepTime = Math.min(retryDelay * 2, MAX_RETRY_DELAY);
                log.warn("{}秒后尝试第{}次重连...", sleepTime / 1000, retryTimes);
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                retryDelay = sleepTime;
            }
        }
    }


    /**
     * 发送心跳保持连接
     */
    private void sendHeartbeat() {
        try {
            // 发送空ack作为心跳
            canalConnector.ack(-1);
            log.debug("发送心跳保持连接");
        } catch (Exception e) {
            log.error("发送心跳失败", e);
            try {
                if (canalConnector.checkValid()) {
                    canalConnector.disconnect();
                }
            } catch (Exception ex) {
                log.error("断开连接出错", ex);
            }
        }
    }

    private void reconnectCanal() {
        try {
            canalConnector.disconnect();
            canalConnector.connect();
            canalConnector.subscribe();
            log.info("成功重新连接到Canal服务器");
        } catch (Exception e) {
            log.error("连接Canal服务器失败", e);
            throw e;
        }
    }

    private void safeRollback(long batchId) {
        try {
            canalConnector.rollback(batchId);
        } catch (Exception ex) {
            log.error("回滚canal消息出错", ex);
            try {
                if (canalConnector.checkValid()) {
                    canalConnector.disconnect();
                }
            } catch (Exception e) {
                log.error("断开连接出错", e);
            }
        }
    }


    private void handleMessage(List<CanalEntry.Entry> entries) {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN || entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                continue;
            }

            CanalEntry.RowChange rowChange;
            try {
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                throw new RuntimeException("解析binlog事件错误", e);
            }

            String tableName = entry.getHeader().getTableName();

            // 只处理我们关心的表
            if (!MONITOR_TABLES.contains(tableName)) {
                continue;
            }

            System.out.println("表名：" + tableName);
            CanalEntry.EventType eventType = rowChange.getEventType();
            String schemaName = entry.getHeader().getSchemaName();

            log.info("======> binlog[{}:{}], name[{},{}], eventType: {}", entry.getHeader().getLogfileName(), entry.getHeader().getLogfileOffset(), schemaName, tableName, eventType);


            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                switch (rowChange.getEventType()) {
                    case INSERT -> handleInsert(rowData.getAfterColumnsList(), tableName);
                    case UPDATE -> handleUpdate(rowData.getAfterColumnsList(), tableName);
                    case DELETE -> handleDelete(rowData.getBeforeColumnsList(), tableName);
                    default -> throw new IllegalStateException("Unknown event type: " + rowChange.getEventType());
                }
            }
        }
    }


    private void handleInsert(List<CanalEntry.Column> columns, String tableName) {
        Map<String, String> map = new HashMap<>();
        for (CanalEntry.Column column : columns) {
            map.put(column.getName(), column.getValue());
        }
        log.info("表名：{}，数据：{}", tableName, map);
        switch (tableName) {
            case "user" -> insertUserToEs(map);
            case "video" -> insertVideoToEs(map);
            case "bullet" -> insertBulletToRedis(map);
        }
    }


    private void insertUserToEs(Map<String, String> map) {
        try {
            UserEs userEs = new UserEs();
            userEs.setId(Long.valueOf(map.get("user_id")));
            userEs.setNickname(map.get("nickname"));
            userEs.setAvatar(map.get("avatar"));
            userEs.setDescription(map.get("description"));
            userEs.setFollowers(0);
            userEs.setVideoCount(0);
            UserEs save = userEsDao.save(userEs);
            log.info("ES 插入用户成功: {}", save);
        } catch (Exception e) {
            log.error("ES 插入失败: ", e);
        }
    }


    private void insertVideoToEs(Map<String, String> map) {
        try {
            VideoEs videoEs = populateVideoEs(map);
            videoEs.setBulletCount(0);
            videoEs.setViewCount(0);
            VideoEs save = videoEsDao.save(videoEs);
            log.info("ES 插入视频成功: {}", save);
        } catch (Exception e) {
            log.error("ES 插入失败: ", e);
        }
    }

    private void insertBulletToRedis(Map<String, String> map) {
        String cacheKey = "video:" + map.get("video_id") + ":bullet";
        String uid = map.get("user_id");
        String id = map.get("bullet_id");
        String content = map.get("content");
        Double timePoint = Double.valueOf(map.get("playback_time"));
        String value = uid + ":" + id + ":" + content;
        try {
            // 预防缓存雪崩
            stringRedisTemplate.opsForZSet().add(cacheKey, value, timePoint);
            stringRedisTemplate.expire(cacheKey, 72 * 3600 + ThreadLocalRandom.current().nextInt(3600), TimeUnit.SECONDS);
            log.info("Redis 插入弹幕成功: {}", value);
        } catch (Exception e) {
            log.error("Redis 插入失败: ", e);
        }
    }

    private void handleUpdate(List<CanalEntry.Column> columns, String tableName) {
        Map<String, String> map = new HashMap<>();
        for (CanalEntry.Column column : columns) {
            map.put(column.getName(), column.getValue());
        }
        log.info("表名：{}，数据：{}", tableName, map);
        switch (tableName) {
            case "user" -> updateUser(map);
            case "video" -> updateVideo(map);
            case "user_stats" -> updateUserStats(map);
            case "video_stats" -> updateVideoStats(map);
        }
    }




    private void updateVideo(Map<String, String> map) {
        try {
            VideoEs videoEs = populateVideoEs(map);
            VideoEs save = videoEsDao.save(videoEs);
            log.info("ES 更新视频成功: {}", save);
        } catch (Exception e) {
            log.error("ES 更新视频失败: ", e);
        }
    }


    private VideoEs populateVideoEs(Map<String, String> map) {
        VideoEs videoEs = new VideoEs();
        videoEs.setId(Long.valueOf(map.get("video_id")));
        videoEs.setCoverUrl(map.get("cover_url"));
        videoEs.setCreateTime(DateUtil.parse(map.get("create_time")));
        videoEs.setDuration(Double.valueOf(map.get("duration")));
        videoEs.setFileUrl(map.get("file_url"));
        videoEs.setNickName(map.get("nickname"));
        videoEs.setTitle(map.get("title"));
        videoEs.setUserId(Long.valueOf(map.get("user_id")));
        return videoEs;
    }



    private void updateUser(Map<String, String> map) {
        try {
            UserEs userEs = new UserEs();
            userEs.setId(Long.valueOf(map.get("user_id")));
            userEs.setNickname(map.get("nickname"));
            userEs.setAvatar(map.get("avatar"));
            userEs.setDescription(map.get("description"));
            UserEs save = userEsDao.save(userEs);
            log.info("ES 更新用户成功: {}", save);
        } catch (Exception e) {
            log.error("ES 更新失败: ", e);
        }
    }





    private void updateVideoStats(Map<String, String> map) {
        updateVideoToEs(map);
        updateVideoToRedis(map);
    }


    private void updateVideoToRedis(Map<String, String> map) {
        try {
            String cacheKey = "videoDetails:" + map.get("video_id");
            if (stringRedisTemplate.hasKey(cacheKey)) {
                Map<String, String> videoDetails = stringRedisTemplate.opsForHash().entries(cacheKey).entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString(), (a, b) -> b, HashMap::new));
                String viewCount = map.get("view_count");
                String bulletCount = map.get("bullet_count");
                String likeCount = map.get("like_count");
                String coinCount = map.get("coin_count");
                String favoriteCount = map.get("favorite_count");
                String commentCount = map.get("comment_count");
                videoDetails.put("viewCount", viewCount);
                videoDetails.put("bulletCount", bulletCount);
                videoDetails.put("likeCount", likeCount);
                videoDetails.put("coinCount", coinCount);
                videoDetails.put("favoriteCount", favoriteCount);
                videoDetails.put("commentCount", commentCount);
                stringRedisTemplate.opsForHash().putAll(cacheKey, videoDetails);
                stringRedisTemplate.expire(cacheKey, VideoConstant.VIDEO_DETAIL_DAYS, TimeUnit.DAYS);
            }

            log.info("Redis 更新视频详情成功");
        } catch (Exception e) {
            log.error("Redis 更新视频详情失败", e);
        }
    }

    private void updateVideoToEs(Map<String, String> map) {
        try {
            Long videoId = Long.valueOf(map.get("video_id"));
            Integer viewCount = Integer.valueOf(map.get("view_count"));
            Integer bulletCount = Integer.valueOf(map.get("bullet_count"));

            // 1. 先从 ES 查出完整文档
            Optional<VideoEs> optional = videoEsDao.findById(videoId);
            if (optional.isEmpty()) {
                log.warn("未找到 videoId={} 的文档，跳过更新", videoId);
                return;
            }

            VideoEs videoEs = optional.get();
            // 2. 只更新关心的字段
            videoEs.setViewCount(viewCount);
            videoEs.setBulletCount(bulletCount);

            // 3. 再保存回去
            VideoEs saved = videoEsDao.save(videoEs);
            log.info("ES 局部更新视频统计成功: {}", saved);
        } catch (Exception e) {
            log.error("ES 更新视频统计失败", e);
        }
    }



    private void updateUserStats(Map<String, String> map) {
        try {
            Long userId = Long.valueOf(map.get("user_id"));
            Integer followers = Integer.valueOf(map.get("followers"));
            Integer videoCount = Integer.valueOf(map.get("video_count"));

            Optional<UserEs> optional = userEsDao.findById(userId);
            if (optional.isEmpty()) {
                log.warn("未找到 userId={} 的文档，跳过更新", userId);
                return;
            }

            UserEs userEs = optional.get();
            userEs.setFollowers(followers);
            userEs.setVideoCount(videoCount);

            UserEs saved = userEsDao.save(userEs);
            log.info("ES 局部更新用户统计成功: {}", saved);
        } catch (Exception e) {
            log.error("ES 更新用户统计失败", e);
        }
    }


    private void handleDelete(List<CanalEntry.Column> columns, String tableName) {
        Map<String, String> map = new HashMap<>();
        for (CanalEntry.Column column : columns) {
            map.put(column.getName(), column.getValue());
        }
        switch (tableName) {
            case "bullet" -> deleteBulletToRedis(map);
            case "user" -> deleteUserToEs(map);
            case "video" -> deleteVideoToEs(map);
        }
    }


    private void deleteUserToEs(Map<String, String> map) {
        try {
            userEsDao.deleteById(Long.valueOf(map.get("user_id")));
            log.info("ES 删除用户成功: {}", map.get("user_id"));
        } catch (Exception e) {
            log.error("ES 删除用户失败: ", e);
        }
    }

    private void deleteVideoToEs(Map<String, String> map) {
        try {
            videoEsDao.deleteById(Long.valueOf(map.get("video_id")));
            log.info("ES 删除视频成功: {}", map.get("video_id"));
        } catch (Exception e) {
            log.error("ES 删除视频成功: ", e);
        }
    }


    private void deleteBulletToRedis(Map<String, String> map) {
        try {
            String vid = map.get("video_id");
            String uid = map.get("user_id");
            String id = map.get("bullet_id");
            String content = map.get("content");
            String cacheKey = "video:" + vid + ":bullet";
            String value = uid + ":" + id + ":" + content;
            stringRedisTemplate.opsForZSet().remove(cacheKey, value);
            log.info("Redis 删除弹幕成功: {}", map.get("bullet_id"));
        } catch (Exception e) {
            log.error("Redis 删除弹幕失败: ", e);
        }
    }

}