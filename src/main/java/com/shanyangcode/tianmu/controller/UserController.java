package com.shanyangcode.tianmu.controller;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.constants.SMSConstant;
import com.shanyangcode.tianmu.entity.User;
import com.shanyangcode.tianmu.model.dto.user.RegisterRequest;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import com.shanyangcode.tianmu.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}

