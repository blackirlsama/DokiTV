package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 关注表
 * @TableName follow
 */
@TableName(value ="`follow`")
@Data
@SuppressWarnings({"all"})
public class Follow implements Serializable {
    /**
     * 关注ID
     */
    @TableId
    private Long followId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 被关注用户ID
     */
    private Long creatorId;

    /**
     * 
     */
    private Date createTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}