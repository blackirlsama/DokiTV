package com.orangecode.tianmu.model.vo.video;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;


@Data
public class VideoDetailsResponse {

    private Long videoId;


    private String fileUrl;

    private Long userId;


    private String title;


    private Integer type;


    private Double duration;

    private String tags;


    private String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createTime;

    /**
     * 播放量
     */
    private Integer viewCount;

    /**
     * 弹幕数
     */
    private Integer bulletCount;

    /**
     * 点赞数
     */
    private Integer likeCount;

    /**
     * 投币数
     */
    private Integer coinCount;

    /**
     * 收藏数
     */
    private Integer favoriteCount;

    /**
     * 评论量
     */
    private Integer commentCount;


    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户头像url
     */
    private String avatar;


}
