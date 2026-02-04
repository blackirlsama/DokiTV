package com.shanyangcode.tianmu.model.entity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 弹幕表实体类

 * 用于存储弹幕系统中的弹幕信息
 * @TableName bullet 对应数据库中的bullet表
 */
@TableName(value ="bullet") // 指定此实体类对应数据库中的bullet表
@Data // 使用Lombok的@Data注解自动生成getter、setter等方法
public class Bullet implements Serializable { // 实现Serializable接口使对象可序列化
    /**
     * 弹幕ID
     * 作为主键使用
     */
    @TableId // 标识此字段为主键
    private Long bulletId;

    /**
     * 视频ID
     * 关联到对应的视频
     */
    private Long videoId;

    /**
     * 用户ID
     * 关联到发送弹幕的用户
     */
    private Long userId;

    /**
     * 弹幕内容
     * 存储用户发送的弹幕文本
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