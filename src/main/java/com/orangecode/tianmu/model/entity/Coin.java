package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 投币表
 * @TableName coin
 */
@TableName(value ="`coin`")
@Data
@SuppressWarnings({"all"})
public class Coin implements Serializable {
    /**
     * 投币ID
     */
    @TableId
    private Long coinId;

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