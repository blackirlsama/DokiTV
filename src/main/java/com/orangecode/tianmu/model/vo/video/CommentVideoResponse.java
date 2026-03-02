package com.orangecode.tianmu.model.vo.video;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

@Data
public class CommentVideoResponse {

    private Long commentId;

    private String content;

    private Long userId;

    private String nickname;

    private String avatar;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createTime;

    private List<CommentResponse> children;

}