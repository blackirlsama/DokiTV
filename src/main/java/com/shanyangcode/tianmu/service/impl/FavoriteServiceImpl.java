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
        // 检测收藏频率是否过快
        crawlerFavoriteDetect(videoActionRequest);

        // 校验判断视频是否存在
        ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoActionRequest.getVideoId()).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);

        // 校验判断用户是否存在
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, videoActionRequest.getUserId()).exists(), ErrorCode.USER_NOT_EXISTS);

        // 查询是否已经收藏
        ThrowUtils.throwIf(!this.lambdaQuery().eq(Favorite::getVideoId, videoActionRequest.getVideoId()).eq(Favorite::getUserId, videoActionRequest.getUserId()).exists(), ErrorCode.VIDEO_FAVORITE_ERROR);

        // 保存收藏记录
        Favorite favoriteVideo = new Favorite();
        favoriteVideo.setVideoId(videoActionRequest.getVideoId());
        favoriteVideo.setUserId(videoActionRequest.getUserId());
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        favoriteVideo.setFavoriteId(snowflake.nextId());
        boolean save = this.save(favoriteVideo);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR);

        // 视频点赞数+1
        boolean updated = videoStatsService.lambdaUpdate().setSql("favorite_count = favorite_count + 1").eq(VideoStats::getVideoId, videoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

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




