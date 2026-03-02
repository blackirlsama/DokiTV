package com.orangecode.tianmu.model.dto.user;

import lombok.Data;

@Data
public class RegisterRequest {

    private String account;

    private String password;

    private String code;

    private String nickname;
}
