package com.shanyangcode.tianmu.controller;

import com.shanyangcode.tianmu.entity.User;
import com.shanyangcode.tianmu.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
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

    //发送邮箱验证码
    // send verification code
    @GetMapping("/sendVerificationCode")
    public String sendVerificationCode(@RequestParam String account) {
        userService.sendVerificationCode(account);
        return "verification code sent successfully";
    }

}

