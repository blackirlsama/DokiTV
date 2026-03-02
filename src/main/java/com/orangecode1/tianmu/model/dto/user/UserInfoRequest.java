package com.orangecode.tianmu.model.dto.user;


import lombok.Data;


/**
 * 关注用户 DTO
 */
@Data
public class UserInfoRequest {


    /**
     * 用户 ID
     */

    private Long userId;


    /**
     * 被关注者 ID
     */

    private Long creatorId;
}
