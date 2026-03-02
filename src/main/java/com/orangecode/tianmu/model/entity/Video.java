package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 视频表
 * @TableName video
 */
@TableName(value ="video")
@Data
public class Video implements Serializable {
    /**
     * 视频 id
     */
    @TableId
    private Long videoId;

    /**
     * 文件URL
     */
    private String fileUrl;

    /**
     * 封面 url
     */
    private String coverUrl;

    /**
     * 投稿用户ID
     */
    private Long userId;

    /**
     * 标题
     */
    private String title;

    /**
     * 类型(1:自制 2:转载)
     */
    private Integer type;

    /**
     * 播放时长(秒)
     */
    private Double duration;

    /**
     * 分类ID
     */
    private Integer categoryId;

    /**
     * 标签
     */
    private String tags;

    /**
     * 简介
     */
    private String description;

    /**
     * 状态(0:下架 1:审核中 2:已发布)
     */
    private Integer status;

    /**
     * 
     */
    private Date createTime;

    /**
     * 
     */
    private Date updateTime;

    /**
     * 删除标记
     */
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}