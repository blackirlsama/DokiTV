package com.orangecode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orangecode.tianmu.model.entity.Follow;

import org.apache.ibatis.annotations.Mapper;


/**
* @author DP
* @description 针对表【follow(关注表)】的数据库操作Mapper
* @createDate 2025-05-07 10:32:56
* @Entity generator.domain.Follow
*/
@Mapper
@SuppressWarnings({"all"})
public interface FollowMapper extends BaseMapper<Follow> {

}




