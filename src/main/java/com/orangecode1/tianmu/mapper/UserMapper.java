package com.orangecode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.vo.user.LoginResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {


    LoginResponse getUserInfo(Long userId);
}