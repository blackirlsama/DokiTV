package com.shanyangcode.tianmu.service.impl;

import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.VideoMapper;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoSubmitRequest;
import com.shanyangcode.tianmu.model.entity.*;
import com.shanyangcode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoDetailsResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoListResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoResponse;
import com.shanyangcode.tianmu.service.*;
import com.shanyangcode.tianmu.utils.MinioUtil;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
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




}




