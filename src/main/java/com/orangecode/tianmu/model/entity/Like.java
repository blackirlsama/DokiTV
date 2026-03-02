package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 点赞表
 * @TableName like
 */
@TableName(value ="`like`")
@Data
public class Like implements Serializable {
    /**
     * 点赞ID
     */
    @TableId
    private Long likeId;

    /**
     * 视频ID
     */
    private Long videoId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 
     */
    private Date createTime;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}