package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 收藏表
 * @TableName favorite
 */
@TableName(value ="`favorite`")
@Data
@SuppressWarnings({"all"})
public class Favorite implements Serializable {
    /**
     * 收藏ID
     */
    @TableId
    private Long favoriteId;

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