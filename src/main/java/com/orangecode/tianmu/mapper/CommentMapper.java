package com.orangecode.tianmu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orangecode.tianmu.model.entity.Comment;

import org.apache.ibatis.annotations.Mapper;


/**
* @author DP
* @description 针对表【comment(评论表)】的数据库操作Mapper
* @createDate 2025-05-07 10:32:46
* @Entity generator.domain.Comment
*/
@Mapper
@SuppressWarnings({"all"})
public interface CommentMapper extends BaseMapper<Comment> {

}




