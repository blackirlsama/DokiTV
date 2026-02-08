package com.shanyangcode.tianmu.service.impl;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.FollowMapper;
import com.shanyangcode.tianmu.model.dto.user.FollowRequest;
import com.shanyangcode.tianmu.model.entity.Follow;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.entity.UserStats;
import com.shanyangcode.tianmu.model.vo.user.UserListResponse;
import com.shanyangcode.tianmu.service.FollowService;
import com.shanyangcode.tianmu.service.UserService;
import com.shanyangcode.tianmu.service.UserStatsService;

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
    private UserService userService; // 用户服务接口，用于处理用户相关操作

    @Resource
    private UserStatsService userStatsService; // 用户统计服务接口，用于处理用户统计数据



    /**
     * 关注用户方法
     * @param followRequest 关注请求对象，包含用户ID和创作者ID
     * @return 返回操作是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class) // 声明事务注解，指定发生Exception类异常时回滚
    public boolean follow(FollowRequest followRequest) {

        // 查询用户是否存在，检查请求中的用户和创作者ID是否都存在于数据库中
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("user_id", followRequest.getUserId(), followRequest.getCreatorId());
        List<User> users = userService.list(queryWrapper);
        ThrowUtils.throwIf(users.size() != 2, ErrorCode.USER_NOT_EXISTS); // 如果查询结果不是2个用户，则抛出用户不存在异常

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
     * @param: followRequest 关注请求对象，包含用户ID和创作者ID
     * @return: boolean 取消关注是否成功
     * @Date 2025/4/10 14:41
     */
    @Override
    @Transactional(rollbackFor = Exception.class) // 使用事务注解，确保方法内所有数据库操作要么全部成功，要么全部回滚
    public boolean chanelFollow(FollowRequest followRequest) {

        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
    // 设置查询条件，同时查询用户ID和创作者ID
        queryWrapper.in("user_id", followRequest.getUserId(), followRequest.getCreatorId());
        List<User> users = userService.list(queryWrapper);
    // 如果查询结果不为2个用户，则抛出用户不存在异常
        ThrowUtils.throwIf(users.size() != 2, ErrorCode.USER_NOT_EXISTS);

    // 创建关注关系的查询条件
        QueryWrapper<Follow> queryFollowWrapper = new QueryWrapper<>();
    // 设置查询条件，查询指定用户对指定创作者的关注记录
        queryFollowWrapper.eq("user_id", followRequest.getUserId()).eq("creator_id", followRequest.getCreatorId());

        // 更新粉丝统计
    // 使用lambda更新表达式，将创作者的粉丝数减1
        boolean updatedFollowers = userStatsService.lambdaUpdate().setSql("followers = followers - 1").eq(UserStats::getUserId, followRequest.getCreatorId()).update();
    // 如果更新失败，抛出系统错误异常
        ThrowUtils.throwIf(!updatedFollowers, ErrorCode.SYSTEM_ERROR, "更新博主粉丝统计失败");

        // 更新关注统计
    // 使用lambda更新表达式，将用户的关注数减1
        boolean updatedFollowing = userStatsService.lambdaUpdate().setSql("following = following - 1").eq(UserStats::getUserId, followRequest.getUserId()).update();
    // 如果更新失败，抛出系统错误异常
        ThrowUtils.throwIf(!updatedFollowing, ErrorCode.SYSTEM_ERROR, "更新用户关注统计失败");


    // 删除关注关系记录并返回删除结果
        return this.remove(queryFollowWrapper);
    }



    @Override
    /**
     * 获取用户关注列表
     * @param userId 用户ID
     * @return 返回用户关注列表信息
     */
    public List<UserListResponse> followList(Long userId) {

        // 查询用户是否存在关注
        // 创建查询条件包装器，查询指定用户的关注记录
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


//    /**
//     * @MethodName getFollowType
//     * @Description 获取两人的关系 0 没有关注  1 已关注  2 相互关注
//     * @param: userId
//     * @param: creatorId
//     * @return: java.lang.Integer
//     * @Date 2025/4/10 14:54
//     */
//    @Override
//    public Integer getFollowType(Long userId, Long creatorId) {
//        Integer followType = 0;
//        boolean existsFollowing = this.lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getCreatorId, creatorId).exists();
//        boolean existsFollower = this.lambdaQuery().eq(Follow::getUserId, creatorId).eq(Follow::getCreatorId, userId).exists();
//        if (existsFollowing && existsFollower) {
//            followType = 2;
//        } else if (existsFollowing && !existsFollower) {
//            followType = 1;
//        }
//        return followType;
//    }
}




