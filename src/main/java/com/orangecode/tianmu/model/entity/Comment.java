package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 评论表
 * @TableName comment
 */
@TableName(value ="`comment`")
@Data
@SuppressWarnings({"all"})
public class Comment implements Serializable {
    /**
     * 评论ID
     */
    @TableId
    private Long commentId;

    /**
     * 视频ID
     */
    private Long videoId;

    /**
     * 评论用户ID
     */
    private Long userId;

    /**
     * 父评论ID
     */
    private Long parentCommentId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 
     */
    private Date createTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}