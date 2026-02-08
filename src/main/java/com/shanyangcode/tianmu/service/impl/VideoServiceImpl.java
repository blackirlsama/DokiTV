package com.shanyangcode.tianmu.service.impl;

import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.BusinessException;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.VideoMapper;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoSubmitRequest;
import com.shanyangcode.tianmu.model.entity.*;
import com.shanyangcode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.shanyangcode.tianmu.model.vo.video.TripleActionResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoDetailsResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoListResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoResponse;
import com.shanyangcode.tianmu.service.*;
import com.shanyangcode.tianmu.utils.MinioUtil;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video>
    implements VideoService {



    @Resource
    private MinioUtil minioUtil;


    @Resource
    private FileService fileService;

    @Resource
    private UserService userService;

    @Resource
    private CategoryService categoryService;


    @Resource
    private VideoStatsService videoStatsService;

    @Resource
    private UserStatsService userStatsService;

    @Resource
    private VideoMapper videoMapper;

    @Resource
    private BulletService bulletService;


    @Resource
    @Lazy
    private LikeService likeService;

    @Resource
    @Lazy
    private  FavoriteService favoriteService;

    @Resource
    @Lazy
    private CoinService coinService;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean submit(VideoSubmitRequest videoSubmitRequest) throws Exception {
        // 判断文件大小
        long size = videoSubmitRequest.getFile().getSize();
        ThrowUtils.throwIf(size > 1024 * 1024 * 1, ErrorCode.FILE_SIZE_ERROR);
        String coverUrl = minioUtil.updateCover(videoSubmitRequest.getFile());

        // 判断文件是否存在
        String fileUrl = videoSubmitRequest.getFileUrl();
        ThrowUtils.throwIf(fileUrl == null || fileUrl.isEmpty() || !fileService.lambdaQuery().eq(File::getFileUrl, videoSubmitRequest.getFileUrl()).exists(), ErrorCode.PARAMS_ERROR);

        // 判断用户是否存在
        Long userId = videoSubmitRequest.getUserId();
        ThrowUtils.throwIf(userId == null || !userService.lambdaQuery().eq(User::getUserId, videoSubmitRequest.getUserId()).exists(), ErrorCode.PARAMS_ERROR);


        // 判断视频标题是否存在
        String title = videoSubmitRequest.getTitle();
        ThrowUtils.throwIf(title == null || title.isEmpty() || StringUtils.isEmpty(title), ErrorCode.PARAMS_ERROR);

        // 判断视频类型是否存在
        Integer type = videoSubmitRequest.getType();
        ThrowUtils.throwIf(type == null || (!type.equals(1) && !type.equals(2)), ErrorCode.PARAMS_ERROR);


        // 判断视频时长是否存在
        Double duration = videoSubmitRequest.getDuration();
        ThrowUtils.throwIf(duration == null, ErrorCode.PARAMS_ERROR);

        // 判断视频分类是否存在
        Integer categoryId = videoSubmitRequest.getCategoryId();
        ThrowUtils.throwIf(categoryId == null || !categoryService.lambdaQuery().eq(Category::getCategoryId, categoryId).exists(), ErrorCode.PARAMS_ERROR);


        // 判断视频标签是否存在
        String tags = videoSubmitRequest.getTags();
        ThrowUtils.throwIf(tags == null || tags.isEmpty(), ErrorCode.PARAMS_ERROR);

        // 判断视频是否已经存在
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        Video video = new Video();
        video.setUserId(userId);
        video.setTitle(title);
        video.setType(type);
        video.setDuration(duration);
        video.setCategoryId(categoryId);
        video.setCoverUrl(coverUrl);
        video.setFileUrl(fileUrl);
        video.setTags(tags);
        video.setVideoId(snowflake.nextId());

        // 保存视频
        boolean resultVideo = this.save(video);
        ThrowUtils.throwIf(!resultVideo, ErrorCode.SYSTEM_ERROR);

        // 保存视频统计
        VideoStats videoStats = new VideoStats();
        videoStats.setVideoId(video.getVideoId());
        boolean resultVideoStats = videoStatsService.save(videoStats);
        ThrowUtils.throwIf(!resultVideoStats, ErrorCode.SYSTEM_ERROR);

        boolean updated = userStatsService.lambdaUpdate().setSql("video_count = video_count + 1").eq(UserStats::getUserId, videoSubmitRequest.getUserId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新用户投稿统计失败");

        // 布隆过滤器添加视频 id
        // BitMapBloomUtil.add(video.getVideoId().toString());
        return true;
    }



    @Override
    public List<VideoListResponse> getVideoList(Integer current, Integer pageSize) {
        if (current == null || current <= 0) {
            return Collections.emptyList(); // 无效页码返回空列表
        }

        // 2. 动态调整 pageSize
        // - 第一页（current=1）加载 11 条
        // - 后续页（current>1）加载 15 条
        int dynamicPageSize = (current == 1) ? 11 : 15;

        // 3. 计算偏移量（offset）
        // - 第一页：offset=0（返回 0~10，共11条）
        // - 第二页：offset=11（跳过前11条，返回 11~25，共15条）
        // - 第三页：offset=26（跳过前26条，返回 26~40，共15条）
        int offset = (current == 1) ? 0 : 11 + (current - 2) * 15;
        System.out.println("offset: " + offset + ", dynamicPageSize: " + dynamicPageSize);
        return videoMapper.selectVideoWithStats(offset, dynamicPageSize);
    }



    @Override
    public VideoResponse videoDetail(VideoActionRequest videoActionRequest) {

        // 校验视频是否存在 通过Bloom过滤器 防止缓存穿透
        // ThrowUtils.throwIf(!BitMapBloomUtil.contains(videoActionRequest.getVideoId().toString()), ErrorCode.VIDEO_NOT_FOUND_ERROR);
        // 获取视频详情
        QueryWrapper<Video> videoQueryWrapper = new QueryWrapper<>();
        videoQueryWrapper.eq("video_id", videoActionRequest.getVideoId());
        Video video = this.getOne(videoQueryWrapper);

        // 增加视频观看次数 使用原子操作更新 VideoStats
        boolean updated = videoStatsService.lambdaUpdate().setSql("view_count = view_count + 1").eq(VideoStats::getVideoId, videoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");
        return publicVideoDetail(videoActionRequest, video);
    }


    public VideoResponse publicVideoDetail(VideoActionRequest videoActionRequest, Video video) {

        // 获取视频详情
        VideoDetailsResponse videoDetails = videoMapper.getVideoDetails(videoActionRequest.getVideoId());

        // 获取弹幕列表
        List<OnlineBulletResponse> onlineBulletResponses = bulletService.getBulletList(videoActionRequest.getVideoId());

        // 获取推荐视频
        List<VideoListResponse> recommendVideoList = videoMapper.recommendVideoList(video.getCategoryId(), video.getVideoId());

        // 封装响应对象
        VideoResponse videoResponse = new VideoResponse();
        videoResponse.setVideoDetailsResponse(videoDetails);
        //videoResponse.setTripleActionResponse(getTripleActionResponse(videoActionRequest));
        videoResponse.setOnlineBulletList(onlineBulletResponses);
        videoResponse.setVideoRecommendListResponse(recommendVideoList);
        //videoResponse.setFollow(followService.getFollowType(videoActionRequest.getUserId(), video.getUserId()));



        return videoResponse;
    }


    @Override
    public List<VideoListResponse> getSubmitVideoList(Long uid) {
        return videoMapper.getSubmitVideoList(uid);
    }

    @Override
    public List<VideoListResponse> getCategoryVideoList(Integer categoryId) {
        return videoMapper.getCategoryVideoList(categoryId);
    }



/**
 * 执行视频三连操作（点赞、收藏、投币）
 * @param videoActionRequest 包含用户ID和视频ID的请求对象
 * @return TripleActionResponse 包含操作结果的响应对象
 * @throws BusinessException 当操作失败或不符合条件时抛出
 */
    @Override
    @Transactional(rollbackFor = Exception.class) // 声明事务，确保所有操作要么全部成功，要么全部回滚
    public TripleActionResponse tripleAction(VideoActionRequest videoActionRequest) {
        // 1. 校验用户和视频
        Long vid = videoActionRequest.getVideoId(); // 获取视频ID
        Long uid = videoActionRequest.getUserId(); // 获取用户ID

    // 检查视频是否存在
        boolean videoExists = this.lambdaQuery().eq(Video::getVideoId, vid).exists();
        ThrowUtils.throwIf(!videoExists, ErrorCode.VIDEO_NOT_FOUND_ERROR);

    // 查询用户信息
        User user = userService.lambdaQuery().eq(User::getUserId, uid).one();

    // 查询用户统计信息（主要是硬币数量）
        UserStats userStats = userStatsService.lambdaQuery().eq(UserStats::getUserId, uid).one();

    // 校验用户是否存在
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

    // 校验用户是否有足够的硬币进行投币
        ThrowUtils.throwIf(userStats.getCoinCount() < 1, ErrorCode.USER_COIN_ERROR);

        // 2. 查询是否已三连（1次查询优化）
    // 检查用户是否已点赞
        boolean hasLiked = likeService.lambdaQuery().eq(Like::getVideoId, videoActionRequest.getVideoId()).eq(Like::getUserId, videoActionRequest.getUserId()).exists();
    // 检查用户是否已收藏
        boolean hasFavorite = favoriteService.lambdaQuery().eq(Favorite::getVideoId, videoActionRequest.getVideoId()).eq(Favorite::getUserId, videoActionRequest.getUserId()).exists();
    // 检查用户是否已投币
        boolean hasCoined = coinService.lambdaQuery().eq(Coin::getVideoId, videoActionRequest.getVideoId()).eq(Coin::getUserId, videoActionRequest.getUserId()).exists();

        // 3. 执行三连操作
        TripleActionResponse response = new TripleActionResponse(); // 创建响应对象
    // 雪花ID生成器，用于生成唯一ID
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
    // 创建视频统计更新的条件构造器
        LambdaUpdateWrapper<VideoStats> statsUpdate = new LambdaUpdateWrapper<VideoStats>().eq(VideoStats::getVideoId, vid);

        // 点赞操作
        if (!hasLiked) {
            Like like = new Like(); // 创建点赞记录
            like.setVideoId(vid); // 设置视频ID
            like.setUserId(uid); // 设置用户ID
            like.setLikeId(snowflake.nextId()); // 生成点赞ID
        // 保存点赞记录，失败则抛出异常
            ThrowUtils.throwIf(!likeService.save(like), ErrorCode.SYSTEM_ERROR);
            response.setLikeId(like.getLikeId()); // 在响应中设置点赞ID
        // 更新视频点赞数统计
            statsUpdate.setSql("like_count = like_count + 1");
        }

        // 收藏操作
        if (!hasFavorite) {
            Favorite favorite = new Favorite(); // 创建收藏记录
            favorite.setVideoId(vid); // 设置视频ID
            favorite.setUserId(uid); // 设置用户ID
            favorite.setFavoriteId(snowflake.nextId()); // 生成收藏ID
        // 保存收藏记录，失败则抛出异常
            ThrowUtils.throwIf(!favoriteService.save(favorite), ErrorCode.SYSTEM_ERROR);
            response.setFavoriteId(favorite.getFavoriteId()); // 在响应中设置收藏ID
        // 更新视频收藏数统计
            statsUpdate.setSql("favorite_count = favorite_count + 1");
        }

        // 投币（原子扣减）
        if (!hasCoined) {
            Coin coin = new Coin();
            coin.setVideoId(vid);
            coin.setUserId(uid);
            coin.setCoinId(snowflake.nextId());
            ThrowUtils.throwIf(!coinService.save(coin), ErrorCode.SYSTEM_ERROR);

            boolean coinDeducted = userStatsService.lambdaUpdate().setSql("coin_count = coin_count - 1").eq(UserStats::getUserId, uid).update();
            ThrowUtils.throwIf(!coinDeducted, ErrorCode.USER_COIN_ERROR, "投币失败，硬币不足");

            response.setCoin(true);
            statsUpdate.setSql("coin_count = coin_count + 1");
        }

        // 4. 更新视频统计
        if (hasLiked && hasFavorite && hasCoined) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "您已经三连过了");
        }
        boolean statsUpdated = videoStatsService.update(statsUpdate);
        ThrowUtils.throwIf(!statsUpdated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return response;
    }



}




