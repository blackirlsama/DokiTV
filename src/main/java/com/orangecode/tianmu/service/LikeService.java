package com.orangecode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.Like;


public interface LikeService extends IService<Like> {


    Long likeVideo(VideoActionRequest likeVideoRequest);


    Boolean cancelLikeVideo(CancelVideoActionRequest cancelVideoActionRequest);
}
