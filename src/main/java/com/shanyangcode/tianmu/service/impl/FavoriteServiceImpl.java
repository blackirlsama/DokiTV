package com.shanyangcode.tianmu.service.impl;


import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.FavoriteMapper;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.entity.Favorite;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.entity.Video;
import com.shanyangcode.tianmu.model.entity.VideoStats;
import com.shanyangcode.tianmu.service.FavoriteService;
import com.shanyangcode.tianmu.service.UserService;
import com.shanyangcode.tianmu.service.VideoService;
import com.shanyangcode.tianmu.service.VideoStatsService;
import com.shanyangcode.tianmu.utils.CounterUtil;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author DP
 * @description 针对表【favorite(收藏表)】的数据库操作Service实现
 * @createDate 2025-05-07 10:32:50
 */
@Service
@SuppressWarnings({"all"})
public class FavoriteServiceImpl extends ServiceImpl<FavoriteMapper, Favorite> implements FavoriteService {


    @Resource
    private VideoStatsService videoStatsService;

    @Resource
    private UserService userService;

    @Resource
    private VideoService videoService;

    @Resource
    private CounterUtil counterUtil;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long favoriteVideo(VideoActionRequest videoActionRequest) {
        // 1. 提取核心参数，避免重复调用get方法，提升代码可读性
        Long videoId = videoActionRequest.getVideoId();
        Long userId = videoActionRequest.getUserId();

        // 2. 检测收藏频率是否过快
        crawlerFavoriteDetect(videoActionRequest);

        // 3. 校验视频是否存在
        boolean videoExists = videoService.lambdaQuery().eq(Video::getVideoId, videoId).exists();
        ThrowUtils.throwIf(!videoExists, ErrorCode.VIDEO_NOT_FOUND_ERROR, "视频不存在，无法收藏");

        // 4. 校验用户是否存在
        boolean userExists = userService.lambdaQuery().eq(User::getUserId, userId).exists();
        ThrowUtils.throwIf(!userExists, ErrorCode.USER_NOT_EXISTS, "用户不存在，无法收藏");

        // 5. 核心修复：查询是否已经收藏（逻辑修正，去掉!取反）
        boolean isFavorited = this.lambdaQuery()
                .eq(Favorite::getVideoId, videoId)
                .eq(Favorite::getUserId, userId)
                .exists();
        // 正确逻辑：如果已收藏（isFavorited=true），则抛出异常
        ThrowUtils.throwIf(isFavorited, ErrorCode.VIDEO_FAVORITE_ERROR, "该视频已收藏，请勿重复收藏");

        // 6. 保存收藏记录
        Favorite favoriteVideo = new Favorite();
        favoriteVideo.setVideoId(videoId);
        favoriteVideo.setUserId(userId);
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        favoriteVideo.setFavoriteId(snowflake.nextId());
        boolean save = this.save(favoriteVideo);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR, "收藏记录保存失败");

        // 7. 视频收藏数+1（修复注释错误：原注释写的是点赞数，实际是收藏数）
        boolean updated = videoStatsService.lambdaUpdate()
                .setSql("favorite_count = favorite_count + 1")
                .eq(VideoStats::getVideoId, videoId)
                .update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频收藏数失败");

        return favoriteVideo.getFavoriteId();
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancelFavoriteVideo(CancelVideoActionRequest cancelVideoActionRequest) {

        // 查询是否存在
        ThrowUtils.throwIf(!this.lambdaQuery().eq(Favorite::getFavoriteId, cancelVideoActionRequest.getId()).exists(), ErrorCode.VIDEO_FAVORITE_NOT_EXISTS);

        // 删除收藏记录
        boolean remove = this.removeById(cancelVideoActionRequest.getId());
        ThrowUtils.throwIf(!remove, ErrorCode.SYSTEM_ERROR);

        // 视频点赞数 -1
        boolean updated = videoStatsService.lambdaUpdate().setSql("favorite_count = favorite_count - 1").eq(VideoStats::getVideoId, cancelVideoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return true;
    }

    private void crawlerFavoriteDetect(VideoActionRequest videoActionRequest) {
        // 调用多少次时告警
        final int WARN_COUNT = 2;
        // 拼接访问 key
        String key = String.format("favorite:%s:%s", videoActionRequest.getUserId(), videoActionRequest.getVideoId());
        // 统计一分钟内访问次数，180 秒过期
        long count = counterUtil.incrAndGetCounter(key, 1, TimeUnit.MINUTES, 80);
        // 是否告警
        ThrowUtils.throwIf(count > WARN_COUNT, ErrorCode.ACCESS_TOO_FREQUENTLY);
    }

}




