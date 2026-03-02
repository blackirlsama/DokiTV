package com.orangecode.tianmu.model.vo.user;

import java.io.Serializable;

import lombok.Data;

@Data
public class UserListResponse implements Serializable {
    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户头像url
     */
    private String avatar;


    /**
     * 个性签名
     */
    private String description;

}