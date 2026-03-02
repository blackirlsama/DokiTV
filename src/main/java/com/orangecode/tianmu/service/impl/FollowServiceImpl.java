package com.orangecode.tianmu.service.impl;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.FollowMapper;
import com.orangecode.tianmu.model.dto.user.FollowRequest;
import com.orangecode.tianmu.model.entity.Follow;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.UserStats;
import com.orangecode.tianmu.model.vo.user.UserListResponse;
import com.orangecode.tianmu.service.FollowService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.UserStatsService;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
* @author DP
* @description 针对表【follow(关注表)】的数据库操作Service实现
* @createDate 2025-05-07 10:32:56
*/
@Service
@SuppressWarnings({"all"})
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow>
    implements FollowService {

    @Resource
    private UserService userService;

    @Resource
    private UserStatsService userStatsService;



    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean follow(FollowRequest followRequest) {

        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("user_id", followRequest.getUserId(), followRequest.getCreatorId());
        List<User> users = userService.list(queryWrapper);
        ThrowUtils.throwIf(users.size() != 2, ErrorCode.USER_NOT_EXISTS);

        // 关注
        Follow follow = new Follow();
        follow.setUserId(followRequest.getUserId());
        follow.setCreatorId(followRequest.getCreatorId());
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        follow.setFollowId(snowflake.nextId());
        boolean saved = this.save(follow);
        ThrowUtils.throwIf(!saved, ErrorCode.SYSTEM_ERROR, "关注失败");

        // 更新粉丝统计
        boolean updatedFollowers = userStatsService.lambdaUpdate().setSql("followers = followers + 1").eq(UserStats::getUserId, followRequest.getCreatorId()).update();
        ThrowUtils.throwIf(!updatedFollowers, ErrorCode.SYSTEM_ERROR, "更新博主粉丝统计失败");

        // 更新关注统计
        boolean updatedFollowing = userStatsService.lambdaUpdate().setSql("following = following + 1").eq(UserStats::getUserId, followRequest.getUserId()).update();
        ThrowUtils.throwIf(!updatedFollowing, ErrorCode.SYSTEM_ERROR, "更新用户关注统计失败");

        return true;
    }


    /**
     * @MethodName chanelFollow
     * @Description 取消关注
     * @param: followRequest
     * @return: boolean
     * @Date 2025/4/10 14:41
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean chanelFollow(FollowRequest followRequest) {

        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("user_id", followRequest.getUserId(), followRequest.getCreatorId());
        List<User> users = userService.list(queryWrapper);
        ThrowUtils.throwIf(users.size() != 2, ErrorCode.USER_NOT_EXISTS);

        QueryWrapper<Follow> queryFollowWrapper = new QueryWrapper<>();
        queryFollowWrapper.eq("user_id", followRequest.getUserId()).eq("creator_id", followRequest.getCreatorId());

        // 更新粉丝统计
        boolean updatedFollowers = userStatsService.lambdaUpdate().setSql("followers = followers - 1").eq(UserStats::getUserId, followRequest.getCreatorId()).update();
        ThrowUtils.throwIf(!updatedFollowers, ErrorCode.SYSTEM_ERROR, "更新博主粉丝统计失败");

        // 更新关注统计
        boolean updatedFollowing = userStatsService.lambdaUpdate().setSql("following = following - 1").eq(UserStats::getUserId, followRequest.getUserId()).update();
        ThrowUtils.throwIf(!updatedFollowing, ErrorCode.SYSTEM_ERROR, "更新用户关注统计失败");


        return this.remove(queryFollowWrapper);
    }



    @Override
    public List<UserListResponse> followList(Long userId) {

        // 查询用户是否存在关注
        QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        List<Follow> followList = this.list(queryWrapper);
        if (followList.size() == 0) {
            return new ArrayList<>();
        }

        // 查询用户信息
        List<User> userList = userService.listByIds(followList.stream().map(Follow::getCreatorId).collect(Collectors.toSet()));
        Map<Long, User> userMap = userList.stream().collect(Collectors.toMap(User::getUserId, user -> user));
        List<UserListResponse> userListResponses = new ArrayList<>();
        for (Follow follow : followList) {
            UserListResponse userListResponse = new UserListResponse();
            userListResponse.setUserId(follow.getCreatorId());
            userListResponse.setAvatar(userMap.get(follow.getCreatorId()).getAvatar());
            userListResponse.setNickname(userMap.get(follow.getCreatorId()).getNickname());
            userListResponse.setDescription(userMap.get(follow.getCreatorId()).getDescription());
            userListResponses.add(userListResponse);
        }
        return userListResponses;
    }



    @Override
    public List<UserListResponse> followerList(Long userId) {

        // 查询用户是否存在关注
        QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("creator_id", userId);
        List<Follow> followList = this.list(queryWrapper);
        if (followList.size() == 0) {
            return new ArrayList<>();
        }

        // 查询用户信息
        List<User> userList = userService.listByIds(followList.stream().map(Follow::getUserId).collect(Collectors.toSet()));
        Map<Long, User> userMap = userList.stream().collect(Collectors.toMap(User::getUserId, user -> user));
        List<UserListResponse> userListResponses = new ArrayList<>();
        for (Follow follow : followList) {
            UserListResponse userListResponse = new UserListResponse();
            userListResponse.setUserId(follow.getUserId());
            userListResponse.setAvatar(userMap.get(follow.getUserId()).getAvatar());
            userListResponse.setNickname(userMap.get(follow.getUserId()).getNickname());
            userListResponse.setDescription(userMap.get(follow.getUserId()).getDescription());
            userListResponses.add(userListResponse);
        }
        return userListResponses;
    }



    @Override
    public Integer getFollowType(Long userId, Long creatorId) {
        Integer followType = 0;
        boolean existsFollowing = this.lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getCreatorId, creatorId).exists();
        boolean existsFollower = this.lambdaQuery().eq(Follow::getUserId, creatorId).eq(Follow::getCreatorId, userId).exists();
        if (existsFollowing && existsFollower) {
            followType = 2;
        } else if (existsFollowing && !existsFollower) {
            followType = 1;
        }
        return followType;
    }
}




