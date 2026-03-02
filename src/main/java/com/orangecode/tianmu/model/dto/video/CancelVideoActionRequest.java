package com.orangecode.tianmu.model.dto.video;

import java.io.Serializable;

import lombok.Data;

@Data
public class CancelVideoActionRequest implements Serializable {


    /**
     * 视频点赞，收藏，评论的 id
     */
    private Long id;


    /**
     * 视频 id
     */
    private Long videoId;
}