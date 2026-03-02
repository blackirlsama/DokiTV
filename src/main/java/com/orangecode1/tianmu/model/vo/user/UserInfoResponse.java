package com.orangecode.tianmu.model.vo.user;

import java.io.Serializable;

import lombok.Data;

@Data
public class UserInfoResponse implements Serializable {

    private Long userId;

    private String phone;

    private String nickname;

    private String avatar;

    private Integer gender;

    private String description;

    private Integer coinCount;

    private Integer followers;

    private Integer following;

    private Integer videoCount;

    private Integer follow; //0 未关注 1 已关注 2 互相关注
}
