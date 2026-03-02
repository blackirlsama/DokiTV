package com.orangecode.tianmu.model.entity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 弹幕表
 * @TableName bullet
 */
@TableName(value ="bullet")
@Data
public class Bullet implements Serializable {
    /**
     * 弹幕ID
     */
    @TableId
    private Long bulletId;

    /**
     * 视频ID
     */
    private Long videoId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 弹幕内容
     */
    private String content;

    /**
     * 弹幕颜色 6位十六进制标准格式
     */
    private String color;

    /**
     * 弹幕所在视频的时间点
     */
    private Double playbackTime;

    /**
     * 日期
     */
    private Date createTime;

    @TableField(exist = false)
    @Serial
    private static final long serialVersionUID = 1L;
}