package com.orangecode.tianmu.model.dto.bullet;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.TableField;

import lombok.Data;


@Data
@SuppressWarnings({"all"})
public class SendBulletRequest implements Serializable {

    /**
     * 弹幕 ID
     */

    private Long bulletId;

    /**
     * 视频 ID
     */

    private Long videoId;

    /**
     * 用户 ID
     */

    private Long userId;

    /**
     * 弹幕内容
     */

    private String content;


    /**
     * 弹幕所在视频的时间点
     */
    private Double playbackTime;

    private String color; // 核心新增：接收前端传的弹幕颜色


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
