package com.shanyangcode.tianmu.model.vo.video;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
/**
 * 评论响应类，用于封装评论相关的响应数据
 * 包含评论的基本信息，如评论ID、内容、用户信息等
 */
@Data
public class CommentResponse {


    /**
     * 评论ID，唯一标识一条评论
     */
    private Long commentId;

    /**
     * 父评论ID，用于表示回复的评论，如果是顶级评论则为null
     */
    private Long parentCommentId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 评论用户的ID
     */
    private Long userId;

    /**
     * 评论用户的昵称
     */
    private String nickname;

    /**
     * 评论用户的头像URL
     */
    private String avatar;

    /**
     * 被回复用户的ID，如果是回复某条评论，则记录被回复用户的ID
     */
    private Long toUserId;

    /**
     * 被回复用户的昵称，如果是回复某条评论，则记录被回复用户的昵称
     */
    private String toNickname;

    /**
     * 评论创建时间
     * 使用@JsonFormat注解指定日期格式为"yyyy-MM-dd HH:mm:ss"，时区为"Asia/Shanghai"
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createTime;


}
