package com.shanyangcode.tianmu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shanyangcode.tianmu.entity.User;

public interface UserService extends IService<User> {
    void sendVerificationCode(String account);
}