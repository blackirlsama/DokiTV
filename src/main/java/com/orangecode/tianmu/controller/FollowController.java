package com.orangecode.tianmu.controller;


import java.util.List;

import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ResultUtils;
import com.orangecode.tianmu.model.dto.user.FollowRequest;
import com.orangecode.tianmu.model.vo.user.UserListResponse;
import com.orangecode.tianmu.service.FollowService;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class FollowController {

    @Resource
    private FollowService followService;


    @PostMapping("/follow")
    public BaseResponse<Boolean> follow(@RequestBody FollowRequest followRequest) {
        return ResultUtils.success(followService.follow(followRequest));
    }


    @PostMapping("/chanel/follow")
    public BaseResponse<Boolean> chanelFollow(@RequestBody FollowRequest followRequest) {
        return ResultUtils.success(followService.chanelFollow(followRequest));
    }


    @GetMapping("/following/list")
    public BaseResponse<List<UserListResponse>> followingList(@NotNull(message = "用户id不能为空")  @RequestParam Long userId) {
        return ResultUtils.success(followService.followList(userId));
    }


    @GetMapping("/followers/list")
    public BaseResponse<List<UserListResponse>> followersList(@NotNull(message = "用户id不能为空") @RequestParam Long userId) {
        return ResultUtils.success(followService.followerList(userId));
    }


}
