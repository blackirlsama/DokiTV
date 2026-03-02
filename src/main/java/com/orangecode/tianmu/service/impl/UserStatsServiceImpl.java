package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.model.entity.UserStats;
import com.orangecode.tianmu.mapper.UserStatsMapper;
import com.orangecode.tianmu.service.UserStatsService;
import org.springframework.stereotype.Service;

@Service
public class UserStatsServiceImpl extends ServiceImpl<UserStatsMapper, UserStats> implements UserStatsService {

}