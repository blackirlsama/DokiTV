package com.shanyangcode.tianmu.service.impl;


import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.CommentMapper;
import com.shanyangcode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.shanyangcode.tianmu.model.dto.video.CreateCommentRequest;
import com.shanyangcode.tianmu.model.entity.Comment;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.entity.VideoStats;
import com.shanyangcode.tianmu.model.vo.video.CommentResponse;
import com.shanyangcode.tianmu.model.vo.video.CommentVideoResponse;
import com.shanyangcode.tianmu.service.CommentService;
import com.shanyangcode.tianmu.service.UserService;
import com.shanyangcode.tianmu.service.VideoStatsService;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author DP
 * @description 针对表【comment(评论表)】的数据库操作Service实现
 * @createDate 2025-05-07 10:32:46
 */
@Service
@SuppressWarnings({"all"})
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Resource
    private UserService userService; // 用户服务，用于获取用户信息

    @Resource
    private VideoStatsService videoStatsService; // 视频统计服务，用于更新视频评论数


    /**
     * 创建视频评论
     * @param createCommentRequest 创建评论请求，包含评论内容、视频ID、用户ID等信息
     * @return CommentResponse 评论响应，包含评论详情和用户信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentResponse createCommentVideo(CreateCommentRequest createCommentRequest) {

        // 创建评论响应对象
        CommentResponse commentResponse = new CommentResponse();
        // 创建评论实体对象并设置基本信息
        Comment comment = new Comment();
        comment.setContent(createCommentRequest.getContent());
        comment.setVideoId(createCommentRequest.getVideoId());
        comment.setUserId(createCommentRequest.getUserId());
        // 使用雪花算法生成唯一ID
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        comment.setCommentId(snowflake.nextId());

        // 如果有父评论，则设置父评论id并检查父评论是否存在
        if (createCommentRequest.getParentCommentId() != null) {
            ThrowUtils.throwIf(!this.lambdaQuery().eq(Comment::getCommentId, createCommentRequest.getParentCommentId()).exists(), ErrorCode.PARENT_COMMENT_NOT_EXISTS);
            comment.setParentCommentId(createCommentRequest.getParentCommentId());
            // 获取父评论信息
            Comment parentComment = this.getById(createCommentRequest.getParentCommentId());
            // 获取父评论用户信息
            User parentUser = userService.lambdaQuery().eq(User::getUserId, parentComment.getUserId()).one();
            // 设置回复的用户ID和昵称
            commentResponse.setToUserId(parentUser.getUserId());
            commentResponse.setToNickname(parentUser.getNickname());
        }

        // 保存评论到数据库
        boolean save = this.save(comment);
        ThrowUtils.throwIf(!save, ErrorCode.CREATE_COMMENT_ERROR);

        // 更新视频评论数
        boolean updatedVideComment = videoStatsService.lambdaUpdate().setSql("comment_count = comment_count + 1").eq(VideoStats::getVideoId, createCommentRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updatedVideComment, ErrorCode.SYSTEM_ERROR, "更新用户评论数数失败");

        // 获取评论的用户信息
        BeanUtil.copyProperties(comment, commentResponse);

        // 获取评论
        Comment commentCreate = this.getById(comment.getCommentId());
        commentResponse.setCreateTime(commentCreate.getCreateTime());
        // 获取评论的用户信息
        User user = userService.lambdaQuery().eq(User::getUserId, comment.getUserId()).one();
        commentResponse.setNickname(user.getNickname());
        commentResponse.setAvatar(user.getAvatar());
        return commentResponse;
    }



/**
 * 删除视频评论并更新视频评论数
 * @param cancelVideoActionRequest 包含评论ID和视频ID的请求对象
 * @return 删除操作是否成功
 * @throws BusinessException 当删除评论失败或更新视频评论数失败时抛出
 */
    @Override
    @Transactional(rollbackFor = Exception.class) // 确保方法中任何异常都会触发事务回滚
    public Boolean deleteCommentVideo(CancelVideoActionRequest cancelVideoActionRequest) {
        // 删除评论
        int result = this.baseMapper.deleteById(cancelVideoActionRequest.getId());
        ThrowUtils.throwIf(result == 0, ErrorCode.DELETE_COMMENT_ERROR);

        Long countComments = this.baseMapper.selectCount(new QueryWrapper<Comment>().eq("video_id", cancelVideoActionRequest.getVideoId()));

        // 更新视频评论数
        boolean updatedVideoComment = videoStatsService.lambdaUpdate().set(VideoStats::getCommentCount, countComments).eq(VideoStats::getVideoId, cancelVideoActionRequest.getVideoId()).update();

        ThrowUtils.throwIf(!updatedVideoComment, ErrorCode.SYSTEM_ERROR, "更新用户评论数失败");

        return true;
    }



/**
 * 获取视频评论列表
 * @param videoId 视频ID
 * @return 评论列表，包含评论信息和用户信息，并构建成评论树结构
 */
    @Override
    public List<CommentVideoResponse> getCommentVideoList(Long videoId) {
        // 1. 获取评论列表并按创建时间升序排序
        QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("video_id", videoId);  // 设置查询条件：视频ID
        queryWrapper.orderByAsc("create_time"); // 按创建时间升序排序

        List<Comment> comments = this.list(queryWrapper); // 查询评论列表

        if (comments.isEmpty()) {  // 如果没有评论，返回空列表
            return new ArrayList<>();
        }

        // 2. 收集用户ID并批量查询
        Set<Long> userIds = comments.stream().map(Comment::getUserId).collect(Collectors.toSet()); // 提取所有评论的用户ID
        Map<Long, User> userMap = userService.listByIds(userIds).stream().collect(Collectors.toMap(User::getUserId, Function.identity())); // 批量查询用户信息并构建映射表

        // 3. 构建评论映射表（commentId -> 评论对象）
        Map<Long, CommentVideoResponse> videoResponseMap = new HashMap<>(); // 顶级评论映射表
        Map<Long, CommentResponse> commentResponseMap = new HashMap<>();   // 子评论映射表
        List<CommentVideoResponse> rootComments = new ArrayList<>();       // 最终返回的顶级评论列表

        // 第一遍遍历：初始化所有评论对象
        for (Comment comment : comments) {
            Long parentId = comment.getParentCommentId();
            if (parentId == null) {
                // 顶级评论 -> TMCommentVideoResponse
                CommentVideoResponse response = new CommentVideoResponse();
                BeanUtil.copyProperties(comment, response); // 复制评论属性
                response.setNickname(userMap.get(comment.getUserId()).getNickname()); // 设置用户昵称
                response.setAvatar(userMap.get(comment.getUserId()).getAvatar());     // 设置用户头像
                response.setChildren(new ArrayList<>()); // 初始化子评论列表
                videoResponseMap.put(comment.getCommentId(), response); // 添加到顶级评论映射表
                rootComments.add(response); // 添加到顶级评论列表
            } else {
                // 子评论 -> TMCommentResponse
                CommentResponse response = new CommentResponse();
                BeanUtil.copyProperties(comment, response); // 复制评论属性
                response.setNickname(userMap.get(comment.getUserId()).getNickname()); // 设置用户昵称
                response.setAvatar(userMap.get(comment.getUserId()).getAvatar());     // 设置用户头像
                commentResponseMap.put(comment.getCommentId(), response); // 添加到子评论映射表
            }
        }

        // 第二遍遍历：构建评论树（平铺所有子评论到顶级评论的 children 中）
        for (Comment comment : comments) {
            Long parentId = comment.getParentCommentId();
            if (parentId != null) {
                // 子评论需要挂到对应的顶级评论下
                Long rootParentId = findRootParentId(comments, parentId); // 查找顶级评论ID
                if (rootParentId != null && videoResponseMap.containsKey(rootParentId)) {
                    CommentResponse current = commentResponseMap.get(comment.getCommentId());
                    // 设置 toUserId 和 toNickname（指向直接父评论）
                    CommentResponse directParent = commentResponseMap.get(parentId);
                    if (directParent != null) {
                        current.setToUserId(directParent.getUserId());      // 设置回复的用户ID
                        current.setToNickname(directParent.getNickname());  // 设置回复的用户昵称
                    } else {
                        // 如果父评论是顶级评论
                        CommentVideoResponse videoParent = videoResponseMap.get(parentId);
                        if (videoParent != null) {
                            current.setToUserId(videoParent.getUserId());      // 设置回复的用户ID
                            current.setToNickname(videoParent.getNickname());  // 设置回复的用户昵称
                        }
                    }
                    // 添加到顶级评论的 children
                    videoResponseMap.get(rootParentId).getChildren().add(current);
                }
            }
        }

        // 4. 对顶级评论按时间降序排序
        rootComments.sort(Comparator.comparing(CommentVideoResponse::getCreateTime).reversed());
        // 5 每个顶级评论的子评论也按 create_time 降序
        for (CommentVideoResponse root : rootComments) {
            root.getChildren().sort(Comparator.comparing(CommentResponse::getCreateTime).reversed());
        }

        return rootComments;
    }

    /**
     * 递归查找评论的顶级父评论ID（parentCommentId == null 的评论）
 * 该方法通过递归方式向上查找，直到找到顶级评论或返回null
 *
 * @param comments 评论列表，包含所有评论数据
 * @param commentId 当前要查找的评论ID
 * @return 返回顶级父评论ID，如果未找到则返回null
     */
    private Long findRootParentId(List<Comment> comments, Long commentId) {
    // 遍历评论列表
        for (Comment comment : comments) {
        // 检查当前遍历的评论是否是要查找的评论
            if (comment.getCommentId().equals(commentId)) {
            // 如果当前评论的父评论ID为null，说明是顶级评论
                if (comment.getParentCommentId() == null) {
                    return commentId; // 找到顶级评论
                } else {
                    return findRootParentId(comments, comment.getParentCommentId()); // 递归向上查找
                }
            }
        }
        return null; // 未找到
    }
}




