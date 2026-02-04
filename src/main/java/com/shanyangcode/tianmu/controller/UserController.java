package com.shanyangcode.tianmu.controller;

import com.shanyangcode.tianmu.common.BaseResponse;
import com.shanyangcode.tianmu.common.ResultUtils;
import com.shanyangcode.tianmu.constants.SMSConstant;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.dto.user.LoginCodeRequest;
import com.shanyangcode.tianmu.model.dto.user.LoginPasswordRequest;
import com.shanyangcode.tianmu.model.dto.user.RegisterRequest;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import com.shanyangcode.tianmu.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 获取所有用户信息（建议仅管理端/测试使用）
     *
     * @return 所有用户实体的列表集合
     */
    @GetMapping
    public List<User> listAllUsers() {
        return userService.list();
    }

    /**
     * 根据用户ID查询单个用户信息
     *
             * @param userId 用户唯一标识ID（路径变量）
            * @return 对应用户的实体信息
     */
    @GetMapping("/{userId}")
    public User getUserById(@PathVariable Long userId) {
        return userService.getById(userId);
    }

    /**
     * 发送短信验证码
     * 用于用户注册、验证码登录前的验证码获取
     *
     * @param account 接收验证码的账号（手机号/邮箱，根据业务实现而定）
     * @return BaseResponse<String> 统一响应结果，返回验证码发送成功提示
     */
    @GetMapping("/sendVerificationCode")
    public BaseResponse<String> sendVerificationCode(@RequestParam String account) {
        userService.sendVerificationCode(account);
        return ResultUtils.success(SMSConstant.SMS_SEND_SUCCESS_MSG);
    }

    /**
     * 用户注册接口
     *
     * @param registerRequest 注册请求体，包含账号、密码、验证码等注册信息
     * @param httpServletRequest 请求对象，可用于获取客户端IP、会话信息等
     * @return BaseResponse<LoginResponse> 统一响应结果，返回注册成功后的登录信息（如token、用户信息）
     */
    @PostMapping("/register")
    public BaseResponse<LoginResponse> register(@RequestBody RegisterRequest registerRequest, HttpServletRequest httpServletRequest) {
        return ResultUtils.success(userService.register(registerRequest, httpServletRequest));
    }

    /**
     * 密码登录接口
     * @Valid 开启请求体参数校验，校验规则在LoginPasswordRequest中定义
     *
     * @param loginPasswordRequest 密码登录请求体，包含账号、密码等信息
     * @param request 请求对象，用于获取客户端信息、生成登录态等
     * @return BaseResponse<LoginResponse> 统一响应结果，返回登录成功信息（token、用户VO等）
     */
    @PostMapping("/loginPassword")
    public BaseResponse<LoginResponse> loginPassword(@Valid @RequestBody LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginPassword(loginPasswordRequest, request));
    }

    /**
     * 验证码登录接口（免密登录）
     * @Valid 开启请求体参数校验，校验规则在LoginCodeRequest中定义
     *
     * @param loginCodeRequest 验证码登录请求体，包含账号、验证码等信息
     * @param request 请求对象，用于获取客户端信息、生成登录态等
     * @return BaseResponse<LoginResponse> 统一响应结果，返回登录成功信息（token、用户VO等）
     */
    @PostMapping("/loginCode")
    public BaseResponse<LoginResponse> loginCode(@Valid @RequestBody LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        return ResultUtils.success(userService.loginCode(loginCodeRequest, request));
    }
}

