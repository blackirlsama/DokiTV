package com.orangecode.tianmu.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 文件表
 * @TableName file
 */
@TableName(value ="`file`")
@Data
public class File implements Serializable {
    /**
     * 文件 id
     */
    @TableId
    private Long fileId;

    /**
     * 文件哈希值
     */
    private String fileHash;

    /**
     * 文件URL
     */
    private String fileUrl;

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