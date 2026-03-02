package com.orangecode.tianmu.model.dto.video;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateCommentRequest implements Serializable {

    /**
     * 评论
     */
    @NotBlank(message = "评论不能为空")
    private String content;

    /**
     * 用户ID
     */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /**
     * 视频ID
     */
    @NotNull(message = "视频 ID 不能为空")
    private Long videoId;

    /**
     * 父评论ID
     */
    private Long parentCommentId;

}