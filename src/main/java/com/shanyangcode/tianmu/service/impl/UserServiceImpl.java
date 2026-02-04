package com.shanyangcode.tianmu.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
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
import com.shanyangcode.tianmu.model.entity.User;
import com.shanyangcode.tianmu.model.entity.UserStats;
import com.shanyangcode.tianmu.model.vo.user.LoginResponse;
import com.shanyangcode.tianmu.service.UserService;
import com.shanyangcode.tianmu.service.UserStatsService;
import com.shanyangcode.tianmu.utils.JwtUtil;
import com.shanyangcode.tianmu.utils.RandomCodeUtil;
import com.shanyangcode.tianmu.utils.SendMailUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserStatsService userStatsService;

    /**
     * 发送用户验证/登录验证码
     * 支持邮箱验证码发送，手机号注册暂不支持；验证码生成后存入Redis并设置过期时间
     * @param account 用户账号（邮箱/手机号），必传
     */
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


    /**
     * 用户注册核心方法
     * 开启全局事务，保证用户信息与统计信息同时保存/回滚；含多轮校验、并发安全处理，注册成功后生成JWT令牌
     * @param registerRequest 注册请求体，含账号、密码、昵称、验证码等核心参数
     * @param request HTTP请求对象，预留扩展使用
     * @return LoginResponse 登录响应对象，含用户基础信息、统计信息、JWT令牌
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest registerRequest, HttpServletRequest request) {
        // 1. 参数校验
        validateRegisterRequest(registerRequest);

        // 2. 验证码校验
        validateVerificationCode(registerRequest.getAccount(), registerRequest.getVerificationCode());

        // 3. 检查用户是否已存在
        checkUserExistence(registerRequest.getAccount());

        // 4. 创建用户
        User newUser = createUser(registerRequest);

        // 5. 保存用户信息(并发安全处理)
        return saveUserAndGenerateToken(newUser, registerRequest.getAccount());
    }


    /**
     * 密码登录方法
     * 支持邮箱/手机号密码登录，密码采用MD5+盐加密校验；登录成功后生成JWT令牌并存入Redis，返回用户全量信息
     * @param loginPasswordRequest 密码登录请求体，含账号、密码
     * @param request HTTP请求对象，预留扩展使用
     * @return LoginResponse 登录响应对象，含用户基础信息、统计信息、JWT令牌
     */
    @Override
    public LoginResponse loginPassword(LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        String account = loginPasswordRequest.getAccount();
        String password = loginPasswordRequest.getPassword();

        // 校验账号格式
        validateAccountFormat(account);

        // 获取当前用户信息
        User user = getCurrentUser(account);

        // 校验密码
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());
        ThrowUtils.throwIf(!encryptedPassword.equals(user.getPassword()), ErrorCode.LOGIN_ERROR);

        // 初始化登录响应对象并赋值用户基础信息
        LoginResponse loginResponse = new LoginResponse();
        BeanUtil.copyProperties(user, loginResponse);

        // 赋值用户统计信息
        UserStats userStats = userStatsService.getById(user.getUserId());
        BeanUtil.copyProperties(userStats, loginResponse);

        // 生成并存储jwt令牌
        String token = JwtUtil.generate(user.getUserId().toString());
        stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

        // 为响应对象设置令牌
        loginResponse.setToken(token);
        return loginResponse;
    }



    /**
     * 验证码登录方法
     * 支持邮箱/手机号验证码登录，验证码单次有效（登录成功后删除Redis缓存）；登录成功后生成JWT令牌
     * @param loginCodeRequest 验证码登录请求体，含账号、验证码
     * @param request HTTP请求对象，预留扩展使用
     * @return LoginResponse 登录响应对象，含用户基础信息、统计信息、JWT令牌
     */
    @Override
    public LoginResponse loginCode(LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        String account = loginCodeRequest.getAccount();
        String code = loginCodeRequest.getCode();

        // 校验账号格式
        validateAccountFormat(account);

        // 获取当前用户信息
        User user = getCurrentUser(account);

        // 校验验证码有效性
        String redisCode = stringRedisTemplate.opsForValue().get(account);
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(code), ErrorCode.LOGIN_ERROR_CODE);

        // 初始化登录响应对象并赋值用户基础信息
        LoginResponse loginResponse = new LoginResponse();
        BeanUtil.copyProperties(user, loginResponse);

        // 赋值用户统计信息
        UserStats userStats = userStatsService.getById(user.getUserId());
        BeanUtil.copyProperties(userStats, loginResponse);

        // 删除redis中存储的验证码（保证验证码单次有效）
        stringRedisTemplate.delete(account);

        // 生成并存储jwt令牌
        String token = JwtUtil.generate(user.getUserId().toString());
        stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

        // 为响应对象设置令牌
        loginResponse.setToken(token);
        return loginResponse;
    }
    // =========================== Private Helpers =============================

    /**
     * 校验注册请求参数的合法性
     * 校验账号格式（邮箱/手机号）、密码非空、昵称非空，不满足则抛出对应参数异常
     * @param request 注册请求体
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
     * 校验验证码的有效性
     * 从Redis中获取对应账号的验证码，校验非空+一致性，不满足则抛出验证码错误异常
     * @param account 用户账号（邮箱/手机号）
     * @param code 用户输入的验证码
     */
    private void validateVerificationCode(String account, String code) {
        String redisCode = stringRedisTemplate.opsForValue().get(account);
        if (StringUtils.isBlank(redisCode) || !redisCode.equals(code)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_ERROR);
        }
    }

    /**
     * 检查用户账号是否已存在
     * 根据账号格式（仅邮箱）查询数据库，已存在则抛出用户已注册异常
     * @param account 用户账号（邮箱/手机号）
     */
    private void checkUserExistence(String account) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            queryWrapper.eq(User::getEmail, account);
        }
        if (this.getOne(queryWrapper) != null) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
    }

    /**
     * 构建并初始化用户实体对象
     * 雪花算法生成用户唯一ID，密码MD5+盐加密，按账号格式赋值邮箱/手机号
     * @param request 注册请求体
     * @return User 初始化完成的用户实体对象
     */
    private User createUser(RegisterRequest request) {
        User user = new User();
        user.setUserId(IdUtil.getSnowflake().nextId());
        user.setNickname(request.getNickname());

        // 设置账号(手机号或邮箱)
        if (request.getAccount().matches(UserConstant.EMAIL_REGEX)) {
            user.setEmail(request.getAccount());
            user.setPhone("");
        }

        // 密码加密
        String password = request.getPassword();
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());
        user.setPassword(encryptedPassword);

        return user;
    }

    /**
     * 保存用户信息+初始化统计信息，生成JWT令牌（并发安全处理）
     * 使用account.intern()做同步锁，防止同一账号并发注册；注册成功后清理Redis验证码
     * @param user 初始化完成的用户实体
     * @param account 用户账号（邮箱/手机号）
     * @return LoginResponse 登录响应对象，含用户全量信息与JWT令牌
     */
    private LoginResponse saveUserAndGenerateToken(User user, String account) {
        synchronized (account.intern()) {
            // 保存用户
            boolean saveSuccess = this.baseMapper.insert(user) > 0;

            // 初始化用户统计信息
            UserStats stats = new UserStats();
            stats.setUserId(user.getUserId());
            boolean saveStatsSuccess = userStatsService.save(stats);

            if (!saveSuccess || !saveStatsSuccess) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户注册失败");
            }

            // 清理验证码
            stringRedisTemplate.delete(account);

            // 生成 Token
            String token = JwtUtil.generate(user.getUserId().toString());
            stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

            // 返回用户信息和 Token
            LoginResponse response = this.baseMapper.getUserInfo(user.getUserId());
            response.setToken(token);
            return response;
        }
    }

    /**
     * 统一校验用户账号格式
     * 校验账号是否为有效邮箱/手机号，不满足则抛出参数格式异常
     * @param account 用户账号（邮箱/手机号）
     */
    private void validateAccountFormat(String account) {
        if (!account.matches(UserConstant.PHONE_REGEX) && !account.matches(UserConstant.EMAIL_REGEX)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号必须是有效的手机号或邮箱");
        }
    }


    /**
     * 根据账号查询当前用户信息
     * 支持邮箱/手机号查询，用户不存在则抛出用户未注册异常
     * @param account 用户账号（邮箱/手机号）
     * @return User 数据库中的用户实体对象
     */
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
}