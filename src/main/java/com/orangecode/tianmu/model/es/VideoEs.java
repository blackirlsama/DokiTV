package com.orangecode.tianmu.model.es;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "video")
@Data
public class VideoEs implements Serializable {
    /**
     * 视频 id
     */
    @Id
    private Long id;


    /**
     * 弹幕数
     */
    @Field(type = FieldType.Integer)
    private Integer bulletCount;


    /**
     * 封面
     */
    @Field(type = FieldType.Text)
    private String coverUrl;

    /**
     * 发布时间
     */
    @Field(type = FieldType.Date)
    private Date createTime;


    /**
     * 播放时长(秒)
     */
    @Field(type = FieldType.Double)
    private Double duration;


    /**
     * 文件 url
     */
    @Field(type = FieldType.Text)
    private String fileUrl;


    /**
     * 投稿用户昵称
     */
    @Field(type = FieldType.Text)
    private String nickName;


    /**
     * 标题
     */
    @Field(type = FieldType.Text, analyzer = "ik_smart", searchAnalyzer = "ik_smart")
    private String title;


    /**
     * 投稿用户 id
     */
    @Field(type = FieldType.Long)
    private Long userId;


    /**
     * 播放量
     */
    @Field(type = FieldType.Integer)
    private Integer viewCount;

    
    @Serial
    private static final long serialVersionUID = 1L;


}