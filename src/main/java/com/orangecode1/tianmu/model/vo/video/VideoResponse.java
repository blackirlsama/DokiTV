package com.orangecode.tianmu.model.vo.video;

import java.util.List;

import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;

import lombok.Data;

@Data
public class VideoResponse {

    private VideoDetailsResponse videoDetailsResponse;

    private List<OnlineBulletResponse> onlineBulletList;

    private TripleActionResponse tripleActionResponse;

    private List<VideoListResponse> videoRecommendListResponse;

    //0 未关注 1 已关注 2 互相关注
    private Integer follow;
}
