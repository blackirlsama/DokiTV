package com.shanyangcode.tianmu.model.dto.user;

import lombok.Data;

@Data
public class RegisterRequest {

    private String account;

    private String password;

    private String verificationCode;

    private String nickname;
}
