package com.orangecode.tianmu.model.vo.video;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

@Data
public class SearchVideoListResponse {
    /**
     * 视频ID
     */
    private Long videoId;


    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 播放量
     */
    private Integer viewCount;

    /**
     * 弹幕数
     */
    private Integer bulletCount;


    /**
     * 文件 url
     */
    private String fileUrl;

    /**
     * 封面 url
     */
    private String coverUrl;

    /**
     * 投稿用户昵称
     */
    private String nickName;

    /**
     * 标题
     */
    private String title;

    /**
     * 播放时长(秒)
     */
    private Double duration;


    /**
     * 发布时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createTime;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}