package com.orangecode.tianmu.mapper;


import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.model.vo.video.FavoriteVideoResponse;
import com.orangecode.tianmu.model.vo.video.VideoDetailsResponse;
import com.orangecode.tianmu.model.vo.video.VideoListResponse;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


@Mapper
public interface VideoMapper extends BaseMapper<Video> {



    List<VideoListResponse> selectVideoWithStats(@Param("current") Integer current, @Param("pageSize") Integer pageSize);


    List<VideoListResponse> recommendVideoList(@Param("categoryId") Integer categoryId, @Param("videoId") Long vid);

    VideoDetailsResponse getVideoDetails(Long videoId);


    List<VideoListResponse> getSubmitVideoList(Long videoId);


    List<VideoListResponse> getCategoryVideoList(Integer categoryId);


    List<VideoListResponse> getLikeVideoList(Long userId);

    List<VideoListResponse> getCoinVideoList(Long userId);

    List<FavoriteVideoResponse> getFavoriteVideoList(Long userId);
}




