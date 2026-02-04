package com.shanyangcode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {


    LoginResponse getUserInfo(Long userId);
}