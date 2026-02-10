package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.model.dto.user.LoginCodeRequest;
import com.shanyangcode.tianmu.model.dto.user.LoginPasswordRequest;
import com.shanyangcode.tianmu.model.dto.user.RegisterRequest;
import com.shanyangcode.tianmu.model.dto.user.UserInfoRequest;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import com.shanyangcode.tianmu.model.vo.user.UserInfoResponse;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;

public interface UserService extends IService<User> {
    void sendVerificationCode(String account);

    LoginResponse register(@RequestBody RegisterRequest registerRequest, HttpServletRequest httpServletRequest);

    LoginResponse loginPassword(LoginPasswordRequest loginPasswordRequest, HttpServletRequest request);

    LoginResponse loginCode(LoginCodeRequest loginCodeRequest, HttpServletRequest request);


    boolean userLogout(Long userId, HttpServletRequest request);

    UserInfoResponse getUserInfo(UserInfoRequest userInfoRequest);
}