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

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    // 获取所有用户
    @GetMapping
    public List<User> listAllUsers() {
        return userService.list();
    }

    // 根据ID获取用户
    @GetMapping("/{userId}")
    public User getUserById(@PathVariable Long userId) {
        return userService.getById(userId);
    }

    // send verification code
    @GetMapping("/sendVerificationCode")
    public BaseResponse<String> sendVerificationCode(@RequestParam String account) {
        userService.sendVerificationCode(account);
        return ResultUtils.success(SMSConstant.SMS_SEND_SUCCESS_MSG);
    }

    // register
    @PostMapping("/register")
    public BaseResponse<LoginResponse> register(@RequestBody RegisterRequest registerRequest, HttpServletRequest httpServletRequest) {
        return ResultUtils.success(userService.register(registerRequest, httpServletRequest));
    }

    // login with passwords
    @PostMapping("/loginPassword")
    public BaseResponse<LoginResponse> loginPassword(@RequestBody LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginPassword(loginPasswordRequest, request));
    }

    // login with verification code
    @PostMapping("/loginCode")
    public BaseResponse<LoginResponse> loginCode(@RequestBody LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginCode(loginCodeRequest, request));
    }


    @GetMapping("/logout")
    public BaseResponse<Boolean> logout(@NotNull(message = "手用户id不能为空") @RequestParam Long userId, HttpServletRequest request) {
        return ResultUtils.success(userService.userLogout(userId, request));
    }

    @PostMapping("/info")
    public BaseResponse<UserInfoResponse> getUserInfo(@RequestBody UserInfoRequest userInfoRequest) {
        return ResultUtils.success(userService.getUserInfo(userInfoRequest));
    }
}

