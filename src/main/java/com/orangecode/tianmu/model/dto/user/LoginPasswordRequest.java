package com.orangecode.tianmu.model.dto.user;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;


/**
 * 用户密码登录 DTO
 */
@Data
public class LoginPasswordRequest {


    /**
     * 手机号
     */
    @NotBlank(message = "手机号/邮箱不能为空")
    private String account;



    /**
     * 密码
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
