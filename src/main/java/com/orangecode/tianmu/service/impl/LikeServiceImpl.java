package com.orangecode.tianmu.service.impl;

import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.LikeMapper;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.Like;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.model.entity.VideoStats;
import com.orangecode.tianmu.service.LikeService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.VideoService;
import com.orangecode.tianmu.service.VideoStatsService;
import com.orangecode.tianmu.utils.CounterUtil;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class LikeServiceImpl extends ServiceImpl<LikeMapper, Like>
    implements LikeService {


    @Resource
    private VideoService videoService;

    @Resource
    private VideoStatsService videoStatsService;

    @Resource
    private UserService userService;

    @Resource
    private CounterUtil counterUtil;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long likeVideo(VideoActionRequest videoActionRequest) {

         //检测点赞频率是否过快
        crawlerLikeDetect(videoActionRequest);

         //校验判断视频是否存在
        ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoActionRequest.getVideoId()).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);


        // 校验判断用户是否存在
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, videoActionRequest.getUserId()).exists(), ErrorCode.USER_NOT_EXISTS);

        // 查询是否已经点赞
        ThrowUtils.throwIf(this.lambdaQuery().eq(Like::getVideoId, videoActionRequest.getVideoId()).eq(Like::getUserId, videoActionRequest.getUserId()).exists(), ErrorCode.VIDEO_LIKED_ERROR);


        // 保存点赞记录
        Like likeVideo = new Like();
        likeVideo.setVideoId(videoActionRequest.getVideoId());
        likeVideo.setUserId(videoActionRequest.getUserId());
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        likeVideo.setLikeId(snowflake.nextId());
        boolean save = this.save(likeVideo);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR);

        // 视频点赞数+1
        boolean updated = videoStatsService.lambdaUpdate().setSql("like_count = like_count + 1").eq(VideoStats::getVideoId, likeVideo.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return likeVideo.getLikeId();



    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancelLikeVideo(CancelVideoActionRequest cancelVideoActionRequest) {
        // 查询是否存在
        ThrowUtils.throwIf(!this.lambdaQuery().eq(Like::getLikeId, cancelVideoActionRequest.getId()).exists(), ErrorCode.VIDEO_LIKED_NOT_EXISTS);

        // 删除点赞记录
        boolean remove = this.removeById(cancelVideoActionRequest.getId());
        ThrowUtils.throwIf(!remove, ErrorCode.SYSTEM_ERROR);

        // 视频点赞数 -1
        boolean updated = videoStatsService.lambdaUpdate().setSql("like_count = like_count - 1").eq(VideoStats::getVideoId, cancelVideoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return true;
    }


    private void crawlerLikeDetect(VideoActionRequest videoActionRequest) {
        // 调用多少次时告警
        final int WARN_COUNT = 2;
        // 拼接访问 key
        String key = String.format("like:%s:%s", videoActionRequest.getUserId(), videoActionRequest.getVideoId());
        // 统计一分钟内访问次数，80 秒过期
        long count = counterUtil.incrAndGetCounter(key, 1, TimeUnit.MINUTES, 80);

        // 是否告警
        ThrowUtils.throwIf(count > WARN_COUNT, ErrorCode.ACCESS_TOO_FREQUENTLY);
    }

}




