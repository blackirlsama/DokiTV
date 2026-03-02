package com.orangecode.tianmu.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.orangecode.tianmu.model.dto.user.FollowRequest;
import com.orangecode.tianmu.model.entity.Follow;
import com.orangecode.tianmu.model.vo.user.UserListResponse;

public interface FollowService extends IService<Follow> {
    boolean follow(FollowRequest focusRequest);

    boolean chanelFollow(FollowRequest focusRequest);

    List<UserListResponse> followList(Long userId);

    List<UserListResponse> followerList(Long userId);


    Integer getFollowType(Long userId, Long parentCommendId);
}