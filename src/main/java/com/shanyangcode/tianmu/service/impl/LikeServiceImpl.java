package com.shanyangcode.tianmu.service.impl;

import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.LikeMapper;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.entity.Like;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.entity.Video;
import com.shanyangcode.tianmu.model.entity.VideoStats;
import com.shanyangcode.tianmu.service.LikeService;
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
 * 点赞服务实现类
 * 继承 ServiceImpl<LikeMapper, Like> 提供基础 CRUD 操作
 * 实现 LikeService 接口定义的业务方法
 */
@Service
public class LikeServiceImpl extends ServiceImpl<LikeMapper, Like>
    implements LikeService {


    /**
     * 注入视频服务
     */
    @Resource
    private VideoService videoService;

    /**
     * 注入视频统计服务
     */
    @Resource
    private VideoStatsService videoStatsService;

    /**
     * 注入用户服务
     */
    @Resource
    private UserService userService;

    /**
     * 注入计数工具类
     */
    @Resource
    private CounterUtil counterUtil;


    /**
     * 点赞视频方法
     * @param videoActionRequest 包含用户ID和视频ID的请求对象
     * @return 返回点赞记录ID
     * @Transactional 声明式事务，发生异常时回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)  // 声明式事务注解，表示该方法发生任何异常时都会回滚事务
    public Long likeVideo(VideoActionRequest videoActionRequest) {

         //检测点赞频率是否过快，防止恶意刷赞
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
        // 使用雪花算法生成唯一ID
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        likeVideo.setLikeId(snowflake.nextId());
        boolean save = this.save(likeVideo);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR);

        // 视频点赞数+1
        boolean updated = videoStatsService.lambdaUpdate().setSql("like_count = like_count + 1").eq(VideoStats::getVideoId, likeVideo.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return likeVideo.getLikeId();



    }



    /**
     * 取消点赞视频方法
     * @param cancelVideoActionRequest 包含点赞ID和视频ID的请求对象
     * @return 操作结果，成功返回true
     * @Transactional 声明式事务，发生异常时回滚
     */
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


    /**
     * 点赞频率检测方法
     * 用于防止恶意刷赞，限制用户对同一视频的点赞频率
     * @param videoActionRequest 包含用户ID和视频ID的请求对象
     */
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




