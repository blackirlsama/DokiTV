package com.orangecode.tianmu.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户验证码登录 DTO
 */
@Data
public class LoginCodeRequest {


    /**
     * 手机号
     */
    @NotBlank(message = "手机号/邮箱不能为空")
    private String account;


    /**
     * 验证码
     */
    @NotBlank(message = "验证码不能为空")
    private String code;
}
