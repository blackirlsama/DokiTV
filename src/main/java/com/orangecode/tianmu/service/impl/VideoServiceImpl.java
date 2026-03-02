package com.orangecode.tianmu.service.impl;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.constants.VideoConstant;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.VideoMapper;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoSubmitRequest;
import com.orangecode.tianmu.model.entity.*;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.orangecode.tianmu.model.vo.video.*;
import com.orangecode.tianmu.service.*;
import com.orangecode.tianmu.utils.BitMapBloomUtil;
import com.orangecode.tianmu.utils.MinioUtil;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    @Lazy
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private FollowService followService;

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
        BitMapBloomUtil.add(video.getVideoId().toString());
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


    public List<OnlineBulletResponse> getVideoBullets(Long videoId) {
        return bulletService.getBulletList(videoId);
    }

    public List<VideoListResponse> getRecommendVideos(Integer categoryId, Long videoId) {
        return videoMapper.recommendVideoList(categoryId, videoId);
    }


    @Override
    public VideoResponse videoDetail(VideoActionRequest videoActionRequest) {

        // 校验视频是否存在 通过Bloom过滤器 防止缓存穿透
        ThrowUtils.throwIf(!BitMapBloomUtil.contains(videoActionRequest.getVideoId().toString()), ErrorCode.VIDEO_NOT_FOUND_ERROR);

        // 获取视频详情
        QueryWrapper<Video> videoQueryWrapper = new QueryWrapper<>();
        videoQueryWrapper.eq("video_id", videoActionRequest.getVideoId());
        Video video = this.getOne(videoQueryWrapper);

        // 增加视频观看次数 使用原子操作更新 VideoStats
        boolean updated = videoStatsService.lambdaUpdate().setSql("view_count = view_count + 1").eq(VideoStats::getVideoId, videoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        if (stringRedisTemplate.hasKey("videoDetails:" + videoActionRequest.getVideoId().toString())) {
            stringRedisTemplate.expire("videoDetails:" + videoActionRequest.getVideoId().toString(), VideoConstant.VIDEO_DETAIL_DAYS, TimeUnit.DAYS);
            return hotVideoDetail(videoActionRequest, video);
        }

        return publicVideoDetail(videoActionRequest, video);
    }


    public VideoResponse publicVideoDetail(VideoActionRequest videoActionRequest, Video video) {

        // 获取视频详情
        VideoDetailsResponse videoDetails = videoMapper.getVideoDetails(videoActionRequest.getVideoId());


        // 封装响应对象
        VideoResponse videoResponse = new VideoResponse();
        videoResponse.setVideoDetailsResponse(videoDetails);
        videoResponse.setTripleActionResponse(getTripleActionResponse(videoActionRequest));
        videoResponse.setOnlineBulletList(getVideoBullets(videoActionRequest.getVideoId()));
        videoResponse.setVideoRecommendListResponse(getRecommendVideos(video.getCategoryId(), videoActionRequest.getVideoId()));
        videoResponse.setFollow(followService.getFollowType(videoActionRequest.getUserId(), video.getUserId()));


        //判断热点视频
        QueryWrapper<VideoStats> videoStatsQueryWrapper = new QueryWrapper<>();
        videoStatsQueryWrapper.eq("video_id", videoActionRequest.getVideoId());
        VideoStats videoStats = videoStatsService.getOne(videoStatsQueryWrapper);
        if (videoStats.getViewCount() >= VideoConstant.HOT_VIDEO_VIEW_COUNT) {
            Map<String, String> redisVideoDetails = new HashMap<>();
            redisVideoDetails.put("videoId", String.valueOf(videoDetails.getVideoId()));
            redisVideoDetails.put("fileUrl", videoDetails.getFileUrl());
            redisVideoDetails.put("userId", String.valueOf(videoDetails.getUserId()));
            redisVideoDetails.put("title", videoDetails.getTitle());
            redisVideoDetails.put("type", String.valueOf(videoDetails.getType()));
            redisVideoDetails.put("duration", String.valueOf(videoDetails.getDuration()));
            redisVideoDetails.put("tags", videoDetails.getTags());
            redisVideoDetails.put("description", videoDetails.getDescription());
            redisVideoDetails.put("createTime", String.valueOf(videoDetails.getCreateTime().getTime()));
            redisVideoDetails.put("viewCount", String.valueOf(videoDetails.getViewCount()));
            redisVideoDetails.put("bulletCount", String.valueOf(videoDetails.getBulletCount()));
            redisVideoDetails.put("likeCount", String.valueOf(videoDetails.getLikeCount()));
            redisVideoDetails.put("coinCount", String.valueOf(videoDetails.getCoinCount()));
            redisVideoDetails.put("favoriteCount", String.valueOf(videoDetails.getFavoriteCount()));
            redisVideoDetails.put("commentCount", String.valueOf(videoDetails.getCommentCount()));
            redisVideoDetails.put("nickname", videoDetails.getNickname());
            redisVideoDetails.put("avatar", videoDetails.getAvatar());
            stringRedisTemplate.opsForHash().putAll("videoDetails:" + videoActionRequest.getVideoId().toString(), redisVideoDetails);
            stringRedisTemplate.expire("videoDetails:" + videoActionRequest.getVideoId().toString(), VideoConstant.VIDEO_DETAIL_DAYS, TimeUnit.DAYS);

        }
        return videoResponse;
    }


    public VideoResponse hotVideoDetail(VideoActionRequest videoActionRequest, Video video) {

        // 获取视频详情
        Map<String, String> redisVideoDetails = stringRedisTemplate.opsForHash().entries("videoDetails:" + videoActionRequest.getVideoId()).entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString(), (a, b) -> b, HashMap::new));

        VideoDetailsResponse videoDetails = new VideoDetailsResponse();
        // 基本视频信息
        videoDetails.setVideoId(Long.parseLong(redisVideoDetails.get("videoId")));
        videoDetails.setFileUrl(redisVideoDetails.get("fileUrl"));
        videoDetails.setUserId(Long.parseLong(redisVideoDetails.get("userId")));
        videoDetails.setTitle(redisVideoDetails.get("title"));
        videoDetails.setType(Integer.parseInt(redisVideoDetails.get("type")));
        videoDetails.setDuration(Double.parseDouble(redisVideoDetails.get("duration")));
        videoDetails.setTags(redisVideoDetails.get("tags"));
        videoDetails.setDescription(redisVideoDetails.get("description"));
        long timestamp = Long.parseLong(redisVideoDetails.get("createTime"));
        videoDetails.setCreateTime(new Date(timestamp));
        videoDetails.setViewCount(Integer.parseInt(redisVideoDetails.get("viewCount")));
        videoDetails.setBulletCount(Integer.parseInt(redisVideoDetails.get("bulletCount")));
        videoDetails.setLikeCount(Integer.parseInt(redisVideoDetails.get("likeCount")));
        videoDetails.setCoinCount(Integer.parseInt(redisVideoDetails.get("coinCount")));
        videoDetails.setFavoriteCount(Integer.parseInt(redisVideoDetails.get("favoriteCount")));
        videoDetails.setCommentCount(Integer.parseInt(redisVideoDetails.get("commentCount")));
        videoDetails.setNickname(redisVideoDetails.get("nickname"));
        videoDetails.setAvatar(redisVideoDetails.get("avatar"));


        // 封装响应对象
        VideoResponse videoResponse = new VideoResponse();
        videoResponse.setVideoDetailsResponse(videoDetails);
        videoResponse.setTripleActionResponse(getTripleActionResponse(videoActionRequest));
        videoResponse.setOnlineBulletList(getVideoBullets(videoActionRequest.getVideoId()));
        videoResponse.setVideoRecommendListResponse(getRecommendVideos(video.getCategoryId(), video.getVideoId()));
        videoResponse.setFollow(followService.getFollowType(videoActionRequest.getUserId(), video.getUserId()));

        return videoResponse;
    }

    public TripleActionResponse getTripleActionResponse(VideoActionRequest videoActionRequest) {
        TripleActionResponse tripleActionResponse = new TripleActionResponse();
        // 是否点赞
        if (videoActionRequest.getUserId() != null) {
            // 判断是否点赞
            Like likeVideo = likeService.lambdaQuery().eq(Like::getVideoId, videoActionRequest.getVideoId()).eq(Like::getUserId, videoActionRequest.getUserId()).one();
            if (likeVideo != null) {
                tripleActionResponse.setLikeId(likeVideo.getLikeId());
            }

            // 是否收藏
            Favorite favoriteVideo = favoriteService.lambdaQuery().eq(Favorite::getVideoId, videoActionRequest.getVideoId()).eq(Favorite::getUserId, videoActionRequest.getUserId()).one();
            if (favoriteVideo != null) {
                tripleActionResponse.setFavoriteId(favoriteVideo.getFavoriteId());
            }

            // 是否投币
            Coin coinVideo = coinService.lambdaQuery().eq(Coin::getVideoId, videoActionRequest.getVideoId()).eq(Coin::getUserId, videoActionRequest.getUserId()).one();
            if (coinVideo != null) {
                tripleActionResponse.setCoin(true);
            }
        }
        return tripleActionResponse;
    }

    @Override
    public List<VideoListResponse> getSubmitVideoList(Long uid) {
        return videoMapper.getSubmitVideoList(uid);
    }

    @Override
    public List<VideoListResponse> getCategoryVideoList(Integer categoryId) {
        return videoMapper.getCategoryVideoList(categoryId);
    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public TripleActionResponse tripleAction(VideoActionRequest videoActionRequest) {
        // 1. 校验用户和视频
        Long vid = videoActionRequest.getVideoId();
        Long uid = videoActionRequest.getUserId();

        boolean videoExists = this.lambdaQuery().eq(Video::getVideoId, vid).exists();
        ThrowUtils.throwIf(!videoExists, ErrorCode.VIDEO_NOT_FOUND_ERROR);

        User user = userService.lambdaQuery().eq(User::getUserId, uid).one();

        UserStats userStats = userStatsService.lambdaQuery().eq(UserStats::getUserId, uid).one();

        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

        ThrowUtils.throwIf(userStats.getCoinCount() < 1, ErrorCode.USER_COIN_ERROR);

        // 2. 查询是否已三连（1次查询优化）
        boolean hasLiked = likeService.lambdaQuery().eq(Like::getVideoId, videoActionRequest.getVideoId()).eq(Like::getUserId, videoActionRequest.getUserId()).exists();
        boolean hasFavorite = favoriteService.lambdaQuery().eq(Favorite::getVideoId, videoActionRequest.getVideoId()).eq(Favorite::getUserId, videoActionRequest.getUserId()).exists();
        boolean hasCoined = coinService.lambdaQuery().eq(Coin::getVideoId, videoActionRequest.getVideoId()).eq(Coin::getUserId, videoActionRequest.getUserId()).exists();

        // 3. 执行三连操作
        TripleActionResponse response = new TripleActionResponse();
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        LambdaUpdateWrapper<VideoStats> statsUpdate = new LambdaUpdateWrapper<VideoStats>().eq(VideoStats::getVideoId, vid);

        // 点赞
        if (!hasLiked) {
            Like like = new Like();
            like.setVideoId(vid);
            like.setUserId(uid);
            like.setLikeId(snowflake.nextId());
            ThrowUtils.throwIf(!likeService.save(like), ErrorCode.SYSTEM_ERROR);
            response.setLikeId(like.getLikeId());
            statsUpdate.setSql("like_count = like_count + 1");
        }

        // 收藏
        if (!hasFavorite) {
            Favorite favorite = new Favorite();
            favorite.setVideoId(vid);
            favorite.setUserId(uid);
            favorite.setFavoriteId(snowflake.nextId());
            ThrowUtils.throwIf(!favoriteService.save(favorite), ErrorCode.SYSTEM_ERROR);
            response.setFavoriteId(favorite.getFavoriteId());
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

    @Override
    public List<FavoriteVideoResponse> getFavoriteVideoList(Long userId) {
        return videoMapper.getFavoriteVideoList(userId);
    }

    @Override
    public List<VideoListResponse> getLikeVideoList(Long userId) {
        return videoMapper.getLikeVideoList(userId);
    }

    @Override
    public List<VideoListResponse> getCoinVideoList(Long userId) {
        return videoMapper.getCoinVideoList(userId);
    }

}




