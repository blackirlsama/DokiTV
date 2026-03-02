package com.orangecode.tianmu.service.impl;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.BulletMapper;
import com.orangecode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.model.entity.Bullet;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.model.entity.VideoStats;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.orangecode.tianmu.service.BulletService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.VideoService;
import com.orangecode.tianmu.service.VideoStatsService;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class BulletServiceImpl extends ServiceImpl<BulletMapper, Bullet> implements BulletService {

    @Resource
    private UserService userService;

    @Resource
    private VideoService videoService;

    @Resource
    private VideoStatsService videoStatsService;


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBulletToMySQL(SendBulletRequest sendBulletRequest) {
        Long videoId = sendBulletRequest.getVideoId();
        Long userId = sendBulletRequest.getUserId();

        // 新增：打印日志，确认方法执行+入参
        System.out.println("保存弹幕入参：videoId=" + videoId + ", userId=" + userId + ", bulletId=" + sendBulletRequest.getBulletId());

        // 校验视频/用户（保留）
        ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoId).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, userId).exists(), ErrorCode.USER_NOT_EXISTS);

        // 初始化video_stats（保留，补充必填字段）
        boolean statsExists = videoStatsService.lambdaQuery().eq(VideoStats::getVideoId, videoId).exists();
        if (!statsExists) {
            VideoStats videoStats = new VideoStats();
            videoStats.setVideoId(videoId);
            videoStats.setBulletCount(0);
            videoStats.setViewCount(0);
            videoStats.setIsDelete(0); // 补充：如果表有is_delete字段，加这个
            videoStats.setCreateTime(new Date()); // 补充：如果表有create_time，加这个
            videoStatsService.save(videoStats);
            System.out.println("初始化video_stats成功，videoId=" + videoId);
        }

        // 更新bullet_count（保留）
        boolean updated = videoStatsService.lambdaUpdate().setSql("bullet_count = bullet_count + 1").eq(VideoStats::getVideoId, videoId).update();
        System.out.println("bullet_count更新结果：" + updated); // 新增日志
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        // 修复：后端生成bulletId，不依赖前端
        Bullet bullet = new Bullet();
        bullet.setVideoId(videoId);
        bullet.setUserId(userId);
        bullet.setContent(sendBulletRequest.getContent());
        bullet.setPlaybackTime(sendBulletRequest.getPlaybackTime());
        bullet.setBulletId(System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1000, 9999));
        boolean saved = this.save(bullet);
        System.out.println("弹幕保存结果：" + saved); // 新增日志
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "保存弹幕失败");
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteVideoBullet(DeleteBulletRequest deleteBulletRequest) {

        Long videoId = deleteBulletRequest.getVideoId();
        Long userId = deleteBulletRequest.getUserId();
        Long bulletId = deleteBulletRequest.getBulletId();

        // 校验视频是否存在（优化为 exists 查询）
        ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoId).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);

        // 校验用户是否存在
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, userId).exists(), ErrorCode.USER_NOT_EXISTS);

        // 校验弹幕是否存在
        ThrowUtils.throwIf(!this.lambdaQuery().eq(Bullet::getBulletId, bulletId).exists(), ErrorCode.BULLET_NOT_EXISTS);

        // 使用原子操作更新 VideoStats
        boolean updated = videoStatsService.lambdaUpdate().setSql("bullet_count = bullet_count - 1").eq(VideoStats::getVideoId, videoId).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        // 保存弹幕
        boolean result = this.removeById(bulletId);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "删除弹幕失败");
        return true;
    }



    @Override
    public List<OnlineBulletResponse> getBulletList(Long videoId) {
        List<OnlineBulletResponse> onlineBulletResponses = new ArrayList<>();
        String cacheKey = "video:" + videoId + ":bullet";

        if (stringRedisTemplate.hasKey(cacheKey)) {
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().rangeWithScores(cacheKey, 0, -1);
            System.out.println(tuples);
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String[] parts = tuple.getValue().split(":");
                OnlineBulletResponse onlineBulletResponse = new OnlineBulletResponse();
                onlineBulletResponse.setUserId(parts[0]);
                onlineBulletResponse.setBulletId(parts[1]);
                onlineBulletResponse.setText(parts[2]);
                onlineBulletResponse.setPlaybackTime(tuple.getScore());
                onlineBulletResponses.add(onlineBulletResponse);
            }



        } else {
            QueryWrapper<Bullet> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("video_Id", videoId);
            List<Bullet> bullets = this.list(queryWrapper);
            if (bullets.isEmpty()) {
                return onlineBulletResponses;
            }
            Set<ZSetOperations.TypedTuple<String>> addTuples = new HashSet<>();
            for (Bullet bullet : bullets) {
                String bulletId = bullet.getBulletId().toString();
                String userId = bullet.getUserId().toString();
                String content = bullet.getContent();
                Double playbackTime = bullet.getPlaybackTime();
                OnlineBulletResponse onlineBulletResponse = new OnlineBulletResponse();
                onlineBulletResponse.setText(content);
                onlineBulletResponse.setPlaybackTime(playbackTime);
                onlineBulletResponse.setBulletId(bulletId);
                onlineBulletResponse.setUserId(userId);
                onlineBulletResponses.add(onlineBulletResponse);
                addTuples.add(new DefaultTypedTuple<>(userId + ":" + bulletId + ":" + content, playbackTime));
            }
            try {
                stringRedisTemplate.opsForZSet().add(cacheKey, addTuples);
                // 随机设置过期时间，防止缓存雪崩
                stringRedisTemplate.expire(cacheKey, 72 * 3600 + ThreadLocalRandom.current().nextInt(3600), TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Redis 保存弹幕失败");
            }
        }

        // 对弹幕按时间排序
        onlineBulletResponses.sort(Comparator.comparingDouble(OnlineBulletResponse::getPlaybackTime));
        return onlineBulletResponses;
    }


    @Override
    public boolean bulletExists(Long bulletId) {
        return this.lambdaQuery().eq(Bullet::getBulletId, bulletId).exists();
    }
}




