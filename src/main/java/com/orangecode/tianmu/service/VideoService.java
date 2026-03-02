package com.orangecode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoSubmitRequest;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.model.vo.video.FavoriteVideoResponse;
import com.orangecode.tianmu.model.vo.video.TripleActionResponse;
import com.orangecode.tianmu.model.vo.video.VideoListResponse;
import com.orangecode.tianmu.model.vo.video.VideoResponse;

/**
 * 视频模块核心服务接口
 */
public interface VideoService extends IService<Video> {

    /**
     * 功能：视频投稿
     * 输入：videoSubmitRequest 投稿参数（文件URL、用户ID、标题等）
     * 输出：是否投稿成功
     */
    boolean submit(VideoSubmitRequest videoSubmitRequest) throws Exception;

    /**
     * 功能：分页查询视频列表
     * 输入：current 页码、pageSize 页大小
     * 输出：视频列表（分页）
     */
    List<VideoListResponse> getVideoList(Integer current, Integer pageSize);

    /**
     * 功能：查询视频详情
     * 输入：videoActionRequest 视频ID参数
     * 输出：视频完整详情
     */
    VideoResponse videoDetail(VideoActionRequest videoActionRequest);

    /**
     * 功能：查询用户投稿的视频列表
     * 输入：userId 用户ID
     * 输出：用户投稿视频列表
     */
    List<VideoListResponse> getSubmitVideoList(Long userId);

    /**
     * 功能：查询指定分类下的视频列表
     * 输入：categoryId 分类ID
     * 输出：该分类下的视频列表
     */
    List<VideoListResponse> getCategoryVideoList(Integer categoryId);

    /**
     * 功能：视频三连操作（点赞+投币+收藏）
     * 输入：videoActionRequest 视频ID+用户ID参数
     * 输出：三连操作结果
     */
    TripleActionResponse tripleAction(VideoActionRequest videoActionRequest);

    /**
     * 功能：查询用户点赞的视频列表
     * 输入：userId 用户ID
     * 输出：用户点赞视频列表
     */
    List<VideoListResponse> getLikeVideoList(Long userId);

    /**
     * 功能：查询用户投币的视频列表
     * 输入：userId 用户ID
     * 输出：用户投币视频列表
     */
    List<VideoListResponse> getCoinVideoList(Long userId);

    /**
     * 功能：查询用户收藏的视频列表
     * 输入：userId 用户ID
     * 输出：用户收藏视频列表
     */
    List<FavoriteVideoResponse> getFavoriteVideoList(Long userId);
}