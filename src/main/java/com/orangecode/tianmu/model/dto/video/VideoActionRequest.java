package com.orangecode.tianmu.model.dto.video;

import java.io.Serializable;

import lombok.Data;

@Data
public class VideoActionRequest implements Serializable {


    /**
     * 用户ID
     */
    private Long userId;


    /**
     * 视频ID
     */
    private Long videoId;
}