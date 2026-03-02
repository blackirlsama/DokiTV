package com.orangecode.tianmu.controller;

import java.util.List;

import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ResultUtils;
import com.orangecode.tianmu.constants.SMSConstant;
import com.orangecode.tianmu.model.dto.user.LoginCodeRequest;
import com.orangecode.tianmu.model.dto.user.LoginPasswordRequest;
import com.orangecode.tianmu.model.dto.user.RegisterRequest;
import com.orangecode.tianmu.model.dto.user.UserInfoRequest;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.vo.user.LoginResponse;
import com.orangecode.tianmu.model.vo.user.UserInfoResponse;
import com.orangecode.tianmu.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 用户模块接口
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 功能：查询所有用户
     * 输入：无
     * 输出：用户列表
     */
    @GetMapping
    public List<User> listAllUsers() {
        return userService.list();
    }

    /**
     * 功能：根据ID查询用户
     * 输入：userId 用户ID
     * 输出：用户详情
     */
    @GetMapping("/{userId}")
    public User getUserById(@PathVariable Long userId) {
        return userService.getById(userId);
    }

    /**
     * 功能：发送验证码
     * 输入：account 手机号/邮箱
     * 输出：发送成功提示
     */
    @GetMapping("/sendVerificationCode")
    public BaseResponse<String> sendVerificationCode(@RequestParam String account) {
        userService.sendVerificationCode(account);
        return ResultUtils.success(SMSConstant.SMS_SEND_SUCCESS_MSG);
    }

    /**
     * 功能：用户注册
     * 输入：registerRequest 注册参数
     * 输出：登录信息
     */
    @PostMapping("/register")
    public BaseResponse<LoginResponse> register(@RequestBody RegisterRequest registerRequest, HttpServletRequest httpServletRequest) {
        return ResultUtils.success(userService.register(registerRequest, httpServletRequest));
    }

    /**
     * 功能：密码登录
     * 输入：loginPasswordRequest 账号密码
     * 输出：登录信息
     */
    @PostMapping("/loginPassword")
    public BaseResponse<LoginResponse> loginPassword(@RequestBody LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginPassword(loginPasswordRequest, request));
    }

    /**
     * 功能：验证码登录
     * 输入：loginCodeRequest 账号验证码
     * 输出：登录信息
     */
    @PostMapping("/loginCode")
    public BaseResponse<LoginResponse> loginCode(@RequestBody LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginCode(loginCodeRequest, request));
    }

    /**
     * 功能：用户登出
     * 输入：userId 用户ID
     * 输出：是否成功
     */
    @GetMapping("/logout")
    public BaseResponse<Boolean> logout(@NotNull(message = "用户id不能为空") @RequestParam Long userId, HttpServletRequest request) {
        return ResultUtils.success(userService.userLogout(userId, request));
    }

    /**
     * 功能：获取用户信息
     * 输入：userInfoRequest 查询参数
     * 输出：脱敏用户信息
     */
    @PostMapping("/info")
    public BaseResponse<UserInfoResponse> getUserInfo(@RequestBody UserInfoRequest userInfoRequest) {
        return ResultUtils.success(userService.getUserInfo(userInfoRequest));
    }
}