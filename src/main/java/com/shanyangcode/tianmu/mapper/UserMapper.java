package com.shanyangcode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanyangcode.tianmu.entity.User;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    LoginResponse getUserInfo(Long userId);
}