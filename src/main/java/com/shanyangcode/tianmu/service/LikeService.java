package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.VideoActionRequest;
import com.shanyangcode.tianmu.model.entity.Like;


public interface LikeService extends IService<Like> {


    Long likeVideo(VideoActionRequest likeVideoRequest);


    Boolean cancelLikeVideo(CancelVideoActionRequest cancelVideoActionRequest);
}
