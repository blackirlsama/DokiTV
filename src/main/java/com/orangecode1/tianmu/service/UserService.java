package com.orangecode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.user.LoginCodeRequest;
import com.orangecode.tianmu.model.dto.user.LoginPasswordRequest;
import com.orangecode.tianmu.model.dto.user.RegisterRequest;
import com.orangecode.tianmu.model.dto.user.UserInfoRequest;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.vo.user.LoginResponse;
import com.orangecode.tianmu.model.vo.user.UserInfoResponse;

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