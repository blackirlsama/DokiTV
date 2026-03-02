package com.orangecode.tianmu.model.dto.video;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频投稿 DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoSubmitRequest implements Serializable {

    /**
     * 视频 URL
     */
    private String fileUrl;


    /**
     * 投稿用户ID
     */
    private Long userId;

    /**
     * 视频文件
     */
    private MultipartFile file;


    /**
     * 标题
     */
    private String title;


    /**
     * 类型(1:自制 2:转载)
     */
    private Integer type;


    /**
     * 播放时长(秒)
     */
    private Double duration;


    /**
     * 分类ID
     */
    private Integer categoryId;


    /**
     * 标签
     */
    private  String tags;


    /**
     * 简介
     */
    private String description;

}
