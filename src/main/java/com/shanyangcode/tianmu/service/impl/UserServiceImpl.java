package com.shanyangcode.tianmu.service.impl;

import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.constants.JWTConstant;
import com.shanyangcode.tianmu.constants.SMSConstant;
import com.shanyangcode.tianmu.constants.UserConstant;
import com.shanyangcode.tianmu.exception.BusinessException;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import com.shanyangcode.tianmu.mapper.UserMapper;
import com.shanyangcode.tianmu.model.dto.user.LoginCodeRequest;
import com.shanyangcode.tianmu.model.dto.user.LoginPasswordRequest;
import com.shanyangcode.tianmu.model.dto.user.RegisterRequest;
import com.shanyangcode.tianmu.model.dto.user.UserInfoRequest;
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.entity.UserStats;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import com.shanyangcode.tianmu.model.vo.user.UserInfoResponse;
import com.shanyangcode.tianmu.service.FollowService;
import com.shanyangcode.tianmu.service.UserService;
import com.shanyangcode.tianmu.service.UserStatsService;
import com.shanyangcode.tianmu.utils.JwtUtil;
import com.shanyangcode.tianmu.utils.RandomCodeUtil;
import com.shanyangcode.tianmu.utils.SendMailUtil;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserStatsService userStatsService;

    @Resource
    @Lazy
    private FollowService followService;


    @Override
    public void sendVerificationCode(String account) {
        // check corner case
        if (StringUtils.isBlank(account)) {
            throw new BusinessException(ErrorCode.PHONE_EMAIL_ERROR);
        }

        // 生成验证码并发送
        String code = RandomCodeUtil.generateSixDigitRandomNumber();
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            SendMailUtil.sendEmailCode(account, code);
        } else if (account.matches(UserConstant.PHONE_REGEX)) {
            throw new BusinessException(ErrorCode.PHONE_REGISTRATION_NOT_SUPPORTED);
        } else {
            throw new BusinessException(ErrorCode.PHONE_EMAIL_ERROR);
        }

        // 保存验证码到 redis, 并设置过期时间
        stringRedisTemplate.opsForValue().set(account, code, SMSConstant.SMS_EXPIRE_TIME, TimeUnit.MINUTES);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest registerRequest, HttpServletRequest request) {
        // 1. 参数校验
        validateRegisterRequest(registerRequest);

        // 2. 验证码校验
        validateVerificationCode(registerRequest.getAccount(), registerRequest.getCode());

        // 3. 检查用户是否已存在
        checkUserExistence(registerRequest.getAccount());

        // 4. 创建用户
        User newUser = createUser(registerRequest);

        // 5. 保存用户信息(并发安全处理)
        return saveUserAndGenerateToken(newUser, registerRequest.getAccount());
    }

    @Override
    public LoginResponse loginPassword(LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        String account = loginPasswordRequest.getAccount();
        String password = loginPasswordRequest.getPassword();

        // check account format
        validateAccountFormat(account);

        // try to get the current user
        User user = getCurrentUser(account);

        // check password
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());
        ThrowUtils.throwIf(!encryptedPassword.equals(user.getPassword()), ErrorCode.LOGIN_ERROR);

        // set user
        LoginResponse loginResponse = new LoginResponse();
        BeanUtil.copyProperties(user, loginResponse);

        // set user-stats
        UserStats userStats = userStatsService.getById(user.getUserId());
        BeanUtil.copyProperties(userStats, loginResponse);

        // generate and store jwt token
        String token = JwtUtil.generate(user.getUserId().toString());
        stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

        loginResponse.setToken(token);
        return loginResponse;
    }

    @Override
    public LoginResponse loginCode(LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        String account = loginCodeRequest.getAccount();
        String code = loginCodeRequest.getCode();

        // check account format
        validateAccountFormat(account);

        // try to get the current user
        User user = getCurrentUser(account);

        // validate verification code
        String redisCode = stringRedisTemplate.opsForValue().get(account);
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(code), ErrorCode.LOGIN_ERROR_CODE);

        // set user
        LoginResponse loginResponse = new LoginResponse();
        BeanUtil.copyProperties(user, loginResponse);

        // set user-stats
        UserStats userStats = userStatsService.getById(user.getUserId());
        BeanUtil.copyProperties(userStats, loginResponse);

        // delete verification code stored in redis
        stringRedisTemplate.delete(account);

        // generate and store jwt token
        String token = JwtUtil.generate(user.getUserId().toString());
        stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

        loginResponse.setToken(token);
        return loginResponse;
    }

    // =========================== Private Helpers =============================

    /**
     * 校验注册请求参数
     */
    private void validateRegisterRequest(RegisterRequest request) {
        String account = request.getAccount();
        if (!account.matches(UserConstant.PHONE_REGEX) && !account.matches(UserConstant.EMAIL_REGEX)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号必须是有效的手机号或邮箱");
        }
        if (StringUtils.isBlank(request.getPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码不能为空");
        }
        if (StringUtils.isBlank(request.getNickname())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "昵称不能为空");
        }
    }

    /**
     * 验证码校验
     */
    private void validateVerificationCode(String account, String code) {
        String redisCode = stringRedisTemplate.opsForValue().get(account);
        if (StringUtils.isBlank(redisCode) || !redisCode.equals(code)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_ERROR);
        }
    }

    /**
     * 检查用户是否已存在
     */
    /**
     * 检查用户是否已存在【邮箱注册修复版】
     */
    private void checkUserExistence(String account) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        // 只处理邮箱（你现在只需要邮箱注册）
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            queryWrapper.eq(User::getEmail, account);
        } else {
            // 非邮箱直接拦截
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持邮箱注册");
        }
        // 用 exists 替代 getOne，杜绝多数据报错/空Wrapper查全表
        boolean exists = this.exists(queryWrapper);
        if (exists) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
    }

    /**
     * 创建用户实体
     */
    /**
     * 创建用户实体【邮箱注册修复版】
     */
    private User createUser(RegisterRequest request) {
        User user = new User();
        user.setUserId(IdUtil.getSnowflake().nextId());
        user.setNickname(request.getNickname());

        // 邮箱注册：强制赋值
        if (request.getAccount().matches(UserConstant.EMAIL_REGEX)) {
            user.setEmail(request.getAccount());
            user.setPhone(""); // 避免数据库非空报错
        }

        // 密码加密
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + request.getPassword()).getBytes());
        user.setPassword(encryptedPassword);

        return user;
    }

    /**
     * 保存用户并生成Token(并发安全处理)
     */
    /**
     * 保存用户并生成Token【邮箱注册修复版】
     */
    private LoginResponse saveUserAndGenerateToken(User user, String account) {
        synchronized (account.intern()) {
            // 1. 保存用户
            boolean saveSuccess = this.save(user);
            // 2. 初始化统计信息
            UserStats stats = new UserStats();
            stats.setUserId(user.getUserId());
            boolean saveStatsSuccess = userStatsService.save(stats);

            if (!saveSuccess || !saveStatsSuccess) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户注册失败");
            }

            // 3. 清理验证码
            stringRedisTemplate.delete(account);

            // 4. 生成Token
            String token = JwtUtil.generate(user.getUserId().toString());
            stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

            // 【关键修复】不用自定义 getUserInfo，手动赋值，绝对不崩
            LoginResponse response = new LoginResponse();
            BeanUtil.copyProperties(user, response);
            BeanUtil.copyProperties(stats, response);
            response.setToken(token);

            return response;
        }
    }

    /**
     * 校验注册请求参数
     */
    private void validateAccountFormat(String account) {
        if (!account.matches(UserConstant.PHONE_REGEX) && !account.matches(UserConstant.EMAIL_REGEX)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号必须是有效的手机号或邮箱");
        }
    }

    private User getCurrentUser(String account) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            queryWrapper.eq(User::getEmail, account);
        } else if (account.matches(UserConstant.PHONE_REGEX)) {
            queryWrapper.eq(User::getPhone, account);
        }

        User user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_EXISTS);
        }

        return user;
    }


    @Override
    public UserInfoResponse getUserInfo(UserInfoRequest userInfoRequest) {
        // 查询用户信息判断用户是否存在
        User user = this.getById(userInfoRequest.getCreatorId());
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

        // 用户基本信息
        UserInfoResponse userInfoResponse = new UserInfoResponse();
        BeanUtil.copyProperties(user, userInfoResponse);

        // 用户统计信息
        UserStats userStats = userStatsService.getById(userInfoRequest.getCreatorId());
        BeanUtil.copyProperties(userStats, userInfoResponse);

        userInfoResponse.setFollow(followService.getFollowType(userInfoRequest.getUserId(), userInfoRequest.getCreatorId()));

        return userInfoResponse;
    }

    @Override
    public boolean userLogout(Long userId, HttpServletRequest request) {
        stringRedisTemplate.delete(userId.toString());
        return true;
    }

}