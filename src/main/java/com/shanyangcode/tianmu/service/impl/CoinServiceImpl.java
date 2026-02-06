package com.shanyangcode.tianmu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.CoinMapper;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.entity.*;
import com.shanyangcode.tianmu.service.*;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * CoinServiceImpl 类实现了 CoinService 接口，提供了硬币相关的业务逻辑实现
 * 使用 @Service 注解标记为服务层组件，并使用 @SuppressWarnings("all") 抑制所有警告
 */
@Service
@SuppressWarnings("all")
public class CoinServiceImpl extends ServiceImpl<CoinMapper, Coin> implements CoinService {


    // 注入视频统计服务
    @Resource
    private VideoStatsService videoStatsService;


    // 注入用户服务
    @Resource
    private UserService userService;

    // 注入视频服务
    @Resource
    private VideoService videoService;

    // 注入用户统计服务
    @Resource
    private UserStatsService userStatsService;


    /**
     * 实现视频投币功能
     * @param videoActionRequest 视频操作请求对象，包含视频ID和用户ID等信息
     * @return 投币成功返回 true，失败会抛出异常
     * @Transactional(rollbackFor = Exception.class) 确保方法内抛出任何异常时都会回滚事务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean coinVideo(VideoActionRequest videoActionRequest) {

        // 校验判断用户是否已经投币
    // 如果用户已经对该视频投过币，则抛出VIDEO_COIN_ERROR异常
        ThrowUtils.throwIf(this.lambdaQuery().eq(Coin::getVideoId, videoActionRequest.getVideoId()).eq(Coin::getUserId, videoActionRequest.getUserId()).exists(), ErrorCode.VIDEO_COIN_ERROR);

        // 校验判断视频是否存在
    // 如果视频不存在，则抛出VIDEO_NOT_FOUND_ERROR异常
        ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoActionRequest.getVideoId()).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);

        // 校验判断用户是否存在
    // 如果用户不存在，则抛出USER_NOT_EXISTS异常
        User user = userService.lambdaQuery().eq(User::getUserId, videoActionRequest.getUserId()).one();
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

        // 获取用户详细信息
    // 从数据库中获取用户的统计信息，包括硬币数量等
        UserStats userStats = userStatsService.getById(user.getUserId());

        // 校验判断用户硬币是否足够
    // 如果用户硬币数量不足1，则抛出USER_COIN_ERROR异常
        ThrowUtils.throwIf(userStats.getCoinCount() < 1, ErrorCode.USER_COIN_ERROR);

        // 保存投币记录
    // 创建新的投币记录对象，设置视频ID、用户ID和生成的唯一ID
        Coin coin = new Coin();
        coin.setVideoId(videoActionRequest.getVideoId());
        coin.setUserId(videoActionRequest.getUserId());
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        coin.setCoinId(snowflake.nextId());
    // 保存投币记录到数据库，如果失败则抛出SYSTEM_ERROR异常
        boolean save = this.save(coin);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR);


        // 视频投币数 +1
    // 更新视频统计信息，将投币数加1，如果更新失败则抛出SYSTEM_ERROR异常
        boolean updatedVideoStats = videoStatsService.lambdaUpdate().setSql("coin_count = coin_count + 1").eq(VideoStats::getVideoId, videoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updatedVideoStats, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");


        // 用户硬币数 -1
    // 更新用户统计信息，将硬币数减1，如果更新失败则抛出SYSTEM_ERROR异常
        boolean updatedUserCoin = userStatsService.lambdaUpdate().setSql("coin_count = coin_count - 1").eq(UserStats::getUserId, videoActionRequest.getUserId()).update();
        ThrowUtils.throwIf(!updatedUserCoin, ErrorCode.SYSTEM_ERROR, "更新用户硬币数失败");

    // 投币操作成功，返回true
        return true;

    }
}




