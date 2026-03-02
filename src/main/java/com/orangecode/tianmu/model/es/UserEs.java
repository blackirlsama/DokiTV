package com.orangecode.tianmu.model.es;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "user")
@Data
public class UserEs implements Serializable {

    /**
     * 用户 id
     */
    @Id
    private Long id;

    /**
     * 头像
     */
    @Field(type = FieldType.Text)
    private String avatar;

    /**
     * 个人描述
     */
    @Field(type = FieldType.Text)
    private String description;


    /**
     * 粉丝数
     */
    @Field(type = FieldType.Integer)
    private Integer followers;


    /**
     * 昵称
     */
    @Field(type = FieldType.Text, analyzer = "ik_smart", searchAnalyzer = "ik_smart")
    private String nickname;


    /**
     * 投稿数
     */
    @Field(type = FieldType.Integer)
    private Integer videoCount;


    @Serial
    private static final long serialVersionUID = 1L;

}