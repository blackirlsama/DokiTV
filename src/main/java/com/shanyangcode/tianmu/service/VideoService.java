package com.shanyangcode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoSubmitRequest;
import com.shanyangcode.tianmu.model.entity.Video;
import com.shanyangcode.tianmu.model.vo.video.FavoriteVideoResponse;
import com.shanyangcode.tianmu.model.vo.video.TripleActionResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoListResponse;
import com.shanyangcode.tianmu.model.vo.video.VideoResponse;


public interface VideoService extends IService<Video> {

    boolean submit(VideoSubmitRequest videoSubmitRequest) throws Exception;


    List<VideoListResponse> getVideoList(Integer current, Integer pageSize);

    VideoResponse videoDetail(VideoActionRequest videoActionRequest);


    List<VideoListResponse> getSubmitVideoList(Long userId);

    List<VideoListResponse> getCategoryVideoList(Integer categoryId);

    TripleActionResponse tripleAction(VideoActionRequest videoActionRequest);


    List<VideoListResponse> getLikeVideoList(Long userId);


    List<VideoListResponse> getCoinVideoList(Long userId);


    List<FavoriteVideoResponse> getFavoriteVideoList(Long userId);
}
