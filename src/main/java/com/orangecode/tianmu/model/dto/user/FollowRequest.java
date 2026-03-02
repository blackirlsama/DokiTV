package com.orangecode.tianmu.model.dto.user;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FollowRequest {


    /**
     * 用户 ID
     */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;


    /**
     * 被关注者 ID
     */
    @NotNull(message = "被关注用户ID 不能为空")
    private Long creatorId;
}