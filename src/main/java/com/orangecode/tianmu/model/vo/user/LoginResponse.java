package com.orangecode.tianmu.model.vo.user;

import lombok.Data;

@Data
public class LoginResponse {

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

    private String token;
}

