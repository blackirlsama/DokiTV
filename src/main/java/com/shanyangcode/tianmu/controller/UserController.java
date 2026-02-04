package com.shanyangcode.tianmu.controller;

import java.util.List;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.constants.SMSConstant;
import com.shanyangcode.tianmu.model.dto.user.LoginCodeRequest;
import com.shanyangcode.tianmu.model.dto.user.LoginPasswordRequest;
import com.shanyangcode.tianmu.model.dto.user.RegisterRequest;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import com.shanyangcode.tianmu.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
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
    public BaseResponse<LoginResponse> loginPassword(@Valid @RequestBody LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginPassword(loginPasswordRequest, request));
    }

    // login with verification code
    @PostMapping("/loginCode")
    public BaseResponse<LoginResponse> loginCode(@Valid @RequestBody LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginCode(loginCodeRequest, request));
    }
}

