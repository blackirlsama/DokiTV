package com.orangecode.tianmu.model.vo.video;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

@Data
public class CommentResponse {


    private Long commentId;

    private Long parentCommentId;

    private String content;

    private Long userId;

    private String nickname;

    private String avatar;

    private Long toUserId;

    private String toNickname;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createTime;


}
