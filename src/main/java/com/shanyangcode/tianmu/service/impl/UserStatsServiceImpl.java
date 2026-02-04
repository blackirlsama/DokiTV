package com.shanyangcode.tianmu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.model.entity.UserStats;
import com.shanyangcode.tianmu.mapper.UserStatsMapper;
import com.shanyangcode.tianmu.service.UserStatsService;
import org.springframework.stereotype.Service;

@Service
public class UserStatsServiceImpl extends ServiceImpl<UserStatsMapper, UserStats> implements UserStatsService {

}