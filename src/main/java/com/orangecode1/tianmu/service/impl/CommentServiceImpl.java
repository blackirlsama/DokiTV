package com.orangecode.tianmu.service.impl;


import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.CommentMapper;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.CreateCommentRequest;
import com.orangecode.tianmu.model.entity.Comment;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.VideoStats;
import com.orangecode.tianmu.model.vo.video.CommentResponse;
import com.orangecode.tianmu.model.vo.video.CommentVideoResponse;
import com.orangecode.tianmu.service.CommentService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.VideoStatsService;

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
    private UserService userService;

    @Resource
    private VideoStatsService videoStatsService;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentResponse createCommentVideo(CreateCommentRequest createCommentRequest) {

        CommentResponse commentResponse = new CommentResponse();
        // 创建评论
        Comment comment = new Comment();
        comment.setContent(createCommentRequest.getContent());
        comment.setVideoId(createCommentRequest.getVideoId());
        comment.setUserId(createCommentRequest.getUserId());
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        comment.setCommentId(snowflake.nextId());

        // 如果有父评论，则设置父评论id
        if (createCommentRequest.getParentCommentId() != null) {
            ThrowUtils.throwIf(!this.lambdaQuery().eq(Comment::getCommentId, createCommentRequest.getParentCommentId()).exists(), ErrorCode.PARENT_COMMENT_NOT_EXISTS);
            comment.setParentCommentId(createCommentRequest.getParentCommentId());
            Comment parentComment = this.getById(createCommentRequest.getParentCommentId());
            User parentUser = userService.lambdaQuery().eq(User::getUserId, parentComment.getUserId()).one();
            commentResponse.setToUserId(parentUser.getUserId());
            commentResponse.setToNickname(parentUser.getNickname());
        }

        // 保存评论
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



    @Override
    @Transactional(rollbackFor = Exception.class)
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



    @Override
    public List<CommentVideoResponse> getCommentVideoList(Long videoId) {
        // 1. 获取评论列表并按创建时间升序排序
        QueryWrapper<Comment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("video_id", videoId);
        queryWrapper.orderByAsc("create_time");

        List<Comment> comments = this.list(queryWrapper);

        if (comments.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 收集用户ID并批量查询
        Set<Long> userIds = comments.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream().collect(Collectors.toMap(User::getUserId, Function.identity()));

        // 3. 构建评论映射表（commentId -> 评论对象）
        Map<Long, CommentVideoResponse> videoResponseMap = new HashMap<>(); // 顶级评论
        Map<Long, CommentResponse> commentResponseMap = new HashMap<>();   // 子评论
        List<CommentVideoResponse> rootComments = new ArrayList<>();       // 最终返回的顶级评论列表

        // 第一遍遍历：初始化所有评论对象
        for (Comment comment : comments) {
            Long parentId = comment.getParentCommentId();
            if (parentId == null) {
                // 顶级评论 -> TMCommentVideoResponse
                CommentVideoResponse response = new CommentVideoResponse();
                BeanUtil.copyProperties(comment, response);
                response.setNickname(userMap.get(comment.getUserId()).getNickname());
                response.setAvatar(userMap.get(comment.getUserId()).getAvatar());
                response.setChildren(new ArrayList<>());
                videoResponseMap.put(comment.getCommentId(), response);
                rootComments.add(response);
            } else {
                // 子评论 -> TMCommentResponse
                CommentResponse response = new CommentResponse();
                BeanUtil.copyProperties(comment, response);
                response.setNickname(userMap.get(comment.getUserId()).getNickname());
                response.setAvatar(userMap.get(comment.getUserId()).getAvatar());
                commentResponseMap.put(comment.getCommentId(), response);
            }
        }

        // 第二遍遍历：构建评论树（平铺所有子评论到顶级评论的 children 中）
        for (Comment comment : comments) {
            Long parentId = comment.getParentCommentId();
            if (parentId != null) {
                // 子评论需要挂到对应的顶级评论下
                Long rootParentId = findRootParentId(comments, parentId);
                if (rootParentId != null && videoResponseMap.containsKey(rootParentId)) {
                    CommentResponse current = commentResponseMap.get(comment.getCommentId());
                    // 设置 toUserId 和 toNickname（指向直接父评论）
                    CommentResponse directParent = commentResponseMap.get(parentId);
                    if (directParent != null) {
                        current.setToUserId(directParent.getUserId());
                        current.setToNickname(directParent.getNickname());
                    } else {
                        // 如果父评论是顶级评论
                        CommentVideoResponse videoParent = videoResponseMap.get(parentId);
                        if (videoParent != null) {
                            current.setToUserId(videoParent.getUserId());
                            current.setToNickname(videoParent.getNickname());
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
     */
    private Long findRootParentId(List<Comment> comments, Long commentId) {
        for (Comment comment : comments) {
            if (comment.getCommentId().equals(commentId)) {
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




