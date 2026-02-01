package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.entity.User;
import com.shanyangcode.tianmu.model.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestBody;

public interface UserService extends IService<User> {
    void sendVerificationCode(String account);
    void register(@RequestBody RegisterRequest registerRequest, HttpServletRequest httpServletRequest);

}
