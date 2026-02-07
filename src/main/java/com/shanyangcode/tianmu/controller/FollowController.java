package com.shanyangcode.tianmu.controller;


import java.util.List;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.model.dto.user.FollowRequest;
import com.shanyangcode.tianmu.model.vo.user.UserListResponse;
import com.shanyangcode.tianmu.service.FollowService;

import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 关注控制器
 * 处理用户关注相关的API请求
 */
@RestController
@RequestMapping("/api/user")
public class FollowController {

    @Resource
    private FollowService followService; // 注入关注服务


    /**
     * 关注用户接口
     * @param followRequest 关注请求参数
     * @return 返回操作结果
     */
    @PostMapping("/follow")
    public BaseResponse<Boolean> follow(@RequestBody FollowRequest followRequest) {
        return ResultUtils.success(followService.follow(followRequest));
    }


    /**
     * 关注频道接口
     * @param followRequest 关注请求参数
     * @return 返回操作结果
     */
    @PostMapping("/chanel/follow")
    public BaseResponse<Boolean> chanelFollow(@RequestBody FollowRequest followRequest) {
        return ResultUtils.success(followService.chanelFollow(followRequest));
    }


    /**
     * 获取用户关注列表接口
     * @param userId 用户ID
     * @return 返回关注列表
     */
    @GetMapping("/following/list")
    public BaseResponse<List<UserListResponse>> followingList(@RequestParam Long userId) {
        return ResultUtils.success(followService.followList(userId));
    }


    /**
     * 获取用户粉丝列表接口
     * @param userId 用户ID
     * @return 返回粉丝列表
     */
    @GetMapping("/followers/list")
    public BaseResponse<List<UserListResponse>> followersList(@RequestParam Long userId) {
        return ResultUtils.success(followService.followerList(userId));
    }


}
