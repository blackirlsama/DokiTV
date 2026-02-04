package com.shanyangcode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanyangcode.tianmu.model.entity.Bullet;

import org.apache.ibatis.annotations.Mapper;

/**
 * BulletMapper接口，继承自BaseMapper
 * 该接口用于处理Bullet实体类的数据库操作
 * 使用MyBatis-Plus的@Mapper注解标记为数据访问层接口
 */
@Mapper
public interface BulletMapper extends BaseMapper<Bullet> {

}