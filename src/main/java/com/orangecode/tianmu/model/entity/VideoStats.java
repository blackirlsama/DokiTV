package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 视频数据统计表
 * @TableName video_stats
 */
@TableName(value ="video_stats")
@Data
public class VideoStats implements Serializable {
    /**
     * 视频ID
     */
    @TableId
    private Long videoId;

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