package com.orangecode.tianmu.model.entity;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("user_stats")
public class UserStats implements Serializable {
    /**
     * id
     */
    @TableId
    private Long userId;

    /**
     * 粉丝数
     */
    private Integer followers;

    /**
     * 关注数
     */
    private Integer following;

    /**
     * 视频数
     */
    private Integer videoCount;


    /**
     * 硬币数
     */
    private Integer coinCount;


    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}