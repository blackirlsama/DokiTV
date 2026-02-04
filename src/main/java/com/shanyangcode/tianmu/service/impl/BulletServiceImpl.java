package com.shanyangcode.tianmu.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.BulletMapper;
import com.shanyangcode.tianmu.model.dto.bullet.DeleteBulletRequest;
import com.shanyangcode.tianmu.model.dto.bullet.SendBulletRequest;
import com.shanyangcode.tianmu.model.entity.Bullet;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.shanyangcode.tianmu.service.BulletService;
import com.shanyangcode.tianmu.service.UserService;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class BulletServiceImpl extends ServiceImpl<BulletMapper, Bullet> implements BulletService {

    @Resource
    private UserService userService;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBulletToMySQL(SendBulletRequest sendBulletRequest) {
        Long videoId = sendBulletRequest.getVideoId();
        Long userId = sendBulletRequest.getUserId();

        // 校验视频是否存在（优化为 exists 查询）
        // ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoId).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);

        // 校验用户是否存在
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, userId).exists(), ErrorCode.USER_NOT_EXISTS);

        // 使用原子操作更新 VideoStats
        // boolean updated = videoStatsService.lambdaUpdate().setSql("bullet_count = bullet_count + 1").eq(VideoStats::getVideoId, videoId).update();
        // ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        // 保存弹幕
        Bullet bullet = new Bullet();
        bullet.setVideoId(videoId);
        bullet.setUserId(userId);
        bullet.setContent(sendBulletRequest.getContent());
        bullet.setPlaybackTime(sendBulletRequest.getPlaybackTime());
        bullet.setBulletId(sendBulletRequest.getBulletId());
        boolean saved = this.save(bullet);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "保存弹幕失败");
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteVideoBullet(DeleteBulletRequest deleteBulletRequest) {

        //Long videoId = deleteBulletRequest.getVideoId();
        Long userId = deleteBulletRequest.getUserId();
        Long bulletId = deleteBulletRequest.getBulletId();

        // 校验视频是否存在（优化为 exists 查询）
        //ThrowUtils.throwIf(!videoService.lambdaQuery().eq(Video::getVideoId, videoId).exists(), ErrorCode.VIDEO_NOT_FOUND_ERROR);

        // 校验用户是否存在
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, userId).exists(), ErrorCode.USER_NOT_EXISTS);

        // 校验弹幕是否存在
        //ThrowUtils.throwIf(!this.lambdaQuery().eq(Bullet::getBulletId, bulletId).exists(), ErrorCode.BULLET_NOT_EXISTS);

        // 使用原子操作更新 VideoStats
        //boolean updated = videoStatsService.lambdaUpdate().setSql("bullet_count = bullet_count - 1").eq(VideoStats::getVideoId, videoId).update();
        //ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        // 保存弹幕
        boolean result = this.removeById(bulletId);
        ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "删除弹幕失败");
        return true;
    }



    @Override
    public List<OnlineBulletResponse> getBulletList(Long videoId) {
        List<OnlineBulletResponse> onlineBulletResponses = new ArrayList<>();

        QueryWrapper<Bullet> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("video_Id", videoId);
        List<Bullet> bullets = this.list(queryWrapper);
        for (Bullet bullet : bullets) {
            OnlineBulletResponse onlineBulletResponse = new OnlineBulletResponse();
            onlineBulletResponse.setText(bullet.getContent());
            onlineBulletResponse.setPlaybackTime(bullet.getPlaybackTime());
            onlineBulletResponse.setBulletId(bullet.getBulletId().toString());
            onlineBulletResponse.setUserId(bullet.getUserId().toString());
            onlineBulletResponses.add(onlineBulletResponse);
        }
        // 对弹幕按时间排序
        onlineBulletResponses.sort(Comparator.comparingDouble(OnlineBulletResponse::getPlaybackTime));
        return onlineBulletResponses;
    }

}




