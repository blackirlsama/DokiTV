package com.orangecode.tianmu.service.impl;

import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.constants.JWTConstant;
import com.orangecode.tianmu.constants.SMSConstant;
import com.orangecode.tianmu.constants.UserConstant;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.UserMapper;
import com.orangecode.tianmu.model.dto.user.LoginCodeRequest;
import com.orangecode.tianmu.model.dto.user.LoginPasswordRequest;
import com.orangecode.tianmu.model.dto.user.RegisterRequest;
import com.orangecode.tianmu.model.dto.user.UserInfoRequest;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.UserStats;
import com.orangecode.tianmu.model.vo.user.LoginResponse;
import com.orangecode.tianmu.model.vo.user.UserInfoResponse;
import com.orangecode.tianmu.service.FollowService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.UserStatsService;
import com.orangecode.tianmu.utils.JwtUtil;
import com.orangecode.tianmu.utils.RandomCodeUtil;
import com.orangecode.tianmu.utils.SendMailUtil;

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

/**
 * 用户服务实现类
 * <p>
 * 继承MyBatis-Plus的ServiceImpl，实现UserService接口，处理用户核心业务逻辑：
 * 1. 验证码发送（邮箱/手机号，当前仅支持邮箱）；
 * 2. 用户注册（含参数校验、验证码校验、并发安全处理、事务管理）；
 * 3. 密码登录/验证码登录（含身份校验、JWT生成与存储）；
 * 4. 用户信息查询（基础信息+统计信息+关注关系）；
 * 5. 用户登出（销毁Redis中的JWT令牌）。
 * <p>
 * 核心特性：
 * - 事务管理：注册接口添加@Transactional保证数据一致性；
 * - 并发安全：注册环节使用synchronized锁避免重复注册；
 * - 数据校验：全链路参数校验，抛出标准化BusinessException；
 * - 安全加密：密码采用MD5+盐值加密，避免明文存储；
 * - 缓存管理：Redis存储验证码、JWT令牌，设置过期时间。
 *
 * @author orangecode
 * @date 2026/02/13
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * Redis模板，用于操作Redis缓存（存储验证码、JWT令牌）
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用户统计服务，用于操作用户统计信息（粉丝数、关注数、视频数等）
     */
    @Autowired
    private UserStatsService userStatsService;

    /**
     * 关注服务，用于查询用户间的关注关系（延迟注入避免循环依赖）
     */
    @Resource
    @Lazy
    private FollowService followService;

    /**
     * 发送验证码（注册/验证码登录前置接口）
     * <p>
     * 核心逻辑：参数校验 → 生成6位随机验证码 → 按账号类型发送（仅支持邮箱，手机号暂不支持） → 验证码存入Redis并设置过期时间。
     *
     * @param account 接收验证码的账号（邮箱/手机号，非空）
     * @throws BusinessException 抛出场景：
     *                           - 账号为空 → ErrorCode.PHONE_EMAIL_ERROR；
     *                           - 账号为手机号 → ErrorCode.PHONE_REGISTRATION_NOT_SUPPORTED；
     *                           - 账号格式既非邮箱也非手机号 → ErrorCode.PHONE_EMAIL_ERROR。
     */
    @Override
    public void sendVerificationCode(String account) {
        // 1. 空值校验：账号不能为空
        if (StringUtils.isBlank(account)) {
            throw new BusinessException(ErrorCode.PHONE_EMAIL_ERROR);
        }

        // 2. 生成6位随机验证码并发送
        String code = RandomCodeUtil.generateSixDigitRandomNumber();
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            // 邮箱验证码：调用邮件工具类发送
            SendMailUtil.sendEmailCode(account, code);
        } else if (account.matches(UserConstant.PHONE_REGEX)) {
            // 手机号验证码：暂不支持，抛出异常
            throw new BusinessException(ErrorCode.PHONE_REGISTRATION_NOT_SUPPORTED);
        } else {
            // 账号格式错误：既非邮箱也非手机号
            throw new BusinessException(ErrorCode.PHONE_EMAIL_ERROR);
        }

        // 3. 验证码存入Redis，过期时间5分钟（SMS_EXPIRE_TIME）
        stringRedisTemplate.opsForValue().set(account, code, SMSConstant.SMS_EXPIRE_TIME, TimeUnit.MINUTES);
    }

    /**
     * 用户注册接口（事务管理）
     * <p>
     * 核心逻辑：参数校验 → 验证码校验 → 账号唯一性校验 → 创建用户实体 → 并发安全保存用户+初始化统计信息 → 生成JWT令牌。
     * 事务注解说明：rollbackFor = Exception.class 表示任何异常都触发事务回滚，保证用户和统计信息要么都保存，要么都回滚。
     *
     * @param registerRequest 注册请求参数（含账号、密码、验证码、昵称）
     * @param request         HTTP请求对象（暂未使用，预留扩展）
     * @return LoginResponse 登录响应VO，包含用户基础信息、统计信息、JWT令牌
     * @throws BusinessException 抛出场景：
     *                           - 参数格式错误 → ErrorCode.PARAMS_ERROR；
     *                           - 验证码错误/过期 → ErrorCode.VERIFICATION_CODE_ERROR；
     *                           - 账号已存在 → ErrorCode.USER_ALREADY_EXISTS；
     *                           - 保存用户/统计信息失败 → ErrorCode.SYSTEM_ERROR。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest registerRequest, HttpServletRequest request) {
        // 1. 注册参数校验（账号格式、密码、昵称非空）
        validateRegisterRequest(registerRequest);

        // 2. 验证码校验（Redis中查询并比对）
        validateVerificationCode(registerRequest.getAccount(), registerRequest.getCode());

        // 3. 账号唯一性校验（仅支持邮箱，避免重复注册）
        checkUserExistence(registerRequest.getAccount());

        // 4. 创建用户实体（密码加密、赋值基础信息）
        User newUser = createUser(registerRequest);

        // 5. 并发安全保存用户+生成JWT令牌（synchronized锁避免重复注册）
        return saveUserAndGenerateToken(newUser, registerRequest.getAccount());
    }

    /**
     * 密码登录接口
     * <p>
     * 核心逻辑：账号格式校验 → 查询用户 → 密码加密校验 → 组装登录响应 → 生成并存储JWT令牌。
     * 密码加密规则：MD5(盐值+明文密码)，盐值固定为UserConstant.PASSWORD_SALT。
     *
     * @param loginPasswordRequest 密码登录请求参数（含账号、明文密码）
     * @param request              HTTP请求对象（暂未使用，预留扩展）
     * @return LoginResponse 登录响应VO，包含用户基础信息、统计信息、JWT令牌
     * @throws BusinessException 抛出场景：
     *                           - 账号格式错误 → ErrorCode.PARAMS_ERROR；
     *                           - 用户不存在 → ErrorCode.USER_NOT_EXISTS；
     *                           - 密码错误 → ErrorCode.LOGIN_ERROR。
     */
    @Override
    public LoginResponse loginPassword(LoginPasswordRequest loginPasswordRequest, HttpServletRequest request) {
        String account = loginPasswordRequest.getAccount();
        String password = loginPasswordRequest.getPassword();

        // 1. 账号格式校验（邮箱/手机号）
        validateAccountFormat(account);

        // 2. 查询用户（按邮箱/手机号匹配）
        User user = getCurrentUser(account);

        // 3. 密码校验：明文密码+盐值MD5加密后比对数据库中的密文
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());
        ThrowUtils.throwIf(!encryptedPassword.equals(user.getPassword()), ErrorCode.LOGIN_ERROR);

        // 4. 组装登录响应VO（用户基础信息+统计信息）
        LoginResponse loginResponse = new LoginResponse();
        BeanUtil.copyProperties(user, loginResponse);
        UserStats userStats = userStatsService.getById(user.getUserId());
        BeanUtil.copyProperties(userStats, loginResponse);

        // 5. 生成JWT令牌并存储到Redis（过期时间JWT_TIME_OUT天）
        String token = JwtUtil.generate(user.getUserId().toString());
        stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

        loginResponse.setToken(token);
        return loginResponse;
    }

    /**
     * 验证码登录接口（免密码登录）
     * <p>
     * 核心逻辑：账号格式校验 → 查询用户 → 验证码校验 → 组装登录响应 → 删除Redis验证码 → 生成并存储JWT令牌。
     * 关键特性：验证码使用后立即删除，避免重复使用。
     *
     * @param loginCodeRequest 验证码登录请求参数（含账号、验证码）
     * @param request          HTTP请求对象（暂未使用，预留扩展）
     * @return LoginResponse 登录响应VO，包含用户基础信息、统计信息、JWT令牌
     * @throws BusinessException 抛出场景：
     *                           - 账号格式错误 → ErrorCode.PARAMS_ERROR；
     *                           - 用户不存在 → ErrorCode.USER_NOT_EXISTS；
     *                           - 验证码错误/过期 → ErrorCode.LOGIN_ERROR_CODE。
     */
    @Override
    public LoginResponse loginCode(LoginCodeRequest loginCodeRequest, HttpServletRequest request) {
        String account = loginCodeRequest.getAccount();
        String code = loginCodeRequest.getCode();

        // 1. 账号格式校验（邮箱/手机号）
        validateAccountFormat(account);

        // 2. 查询用户（按邮箱/手机号匹配）
        User user = getCurrentUser(account);

        // 3. 验证码校验（Redis中查询并比对）
        String redisCode = stringRedisTemplate.opsForValue().get(account);
        ThrowUtils.throwIf(redisCode == null || !redisCode.equals(code), ErrorCode.LOGIN_ERROR_CODE);

        // 4. 组装登录响应VO（用户基础信息+统计信息）
        LoginResponse loginResponse = new LoginResponse();
        BeanUtil.copyProperties(user, loginResponse);
        UserStats userStats = userStatsService.getById(user.getUserId());
        BeanUtil.copyProperties(userStats, loginResponse);

        // 5. 删除Redis中的验证码，避免重复使用
        stringRedisTemplate.delete(account);

        // 6. 生成JWT令牌并存储到Redis（过期时间JWT_TIME_OUT天）
        String token = JwtUtil.generate(user.getUserId().toString());
        stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

        loginResponse.setToken(token);
        return loginResponse;
    }

    /**
     * 查询用户信息（脱敏返回）
     * <p>
     * 核心逻辑：查询用户基础信息 → 查询用户统计信息 → 查询关注关系 → 组装并返回脱敏后的用户信息。
     * 脱敏规则：敏感字段（如密码、手机号）已在VO中过滤，仅返回展示字段。
     *
     * @param userInfoRequest 用户信息查询请求参数（含当前用户ID、目标用户ID）
     * @return UserInfoResponse 用户信息响应VO，包含基础信息、统计信息、关注关系
     * @throws BusinessException 抛出场景：目标用户不存在 → ErrorCode.USER_NOT_EXISTS。
     */
    @Override
    public UserInfoResponse getUserInfo(UserInfoRequest userInfoRequest) {
        // 1. 查询目标用户基础信息，校验用户是否存在
        User user = this.getById(userInfoRequest.getCreatorId());
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

        // 2. 组装用户基础信息到响应VO
        UserInfoResponse userInfoResponse = new UserInfoResponse();
        BeanUtil.copyProperties(user, userInfoResponse);

        // 3. 组装用户统计信息（粉丝数、关注数、视频数等）
        UserStats userStats = userStatsService.getById(userInfoRequest.getCreatorId());
        BeanUtil.copyProperties(userStats, userInfoResponse);

        // 4. 补充关注关系（当前用户是否关注目标用户）
        userInfoResponse.setFollow(followService.getFollowType(userInfoRequest.getUserId(), userInfoRequest.getCreatorId()));

        return userInfoResponse;
    }

    /**
     * 用户登出接口
     * <p>
     * 核心逻辑：删除Redis中存储的用户JWT令牌，销毁登录态。
     *
     * @param userId  用户唯一标识ID（非空）
     * @param request HTTP请求对象（暂未使用，预留扩展）
     * @return boolean 登出结果：true-成功，false-失败（当前逻辑恒返回true）
     */
    @Override
    public boolean userLogout(Long userId, HttpServletRequest request) {
        // 删除Redis中的JWT令牌，销毁登录态
        stringRedisTemplate.delete(userId.toString());
        return true;
    }

    // =========================== 私有辅助方法 =============================

    /**
     * 校验注册请求参数
     * <p>
     * 校验规则：
     * 1. 账号必须是有效的邮箱或手机号；
     * 2. 密码不能为空；
     * 3. 昵称不能为空。
     *
     * @param request 注册请求参数
     * @throws BusinessException 抛出场景：参数不符合规则 → ErrorCode.PARAMS_ERROR（含具体错误信息）。
     */
    private void validateRegisterRequest(RegisterRequest request) {
        String account = request.getAccount();
        // 账号格式校验：必须是邮箱或手机号
        if (!account.matches(UserConstant.PHONE_REGEX) && !account.matches(UserConstant.EMAIL_REGEX)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号必须是有效的手机号或邮箱");
        }
        // 密码非空校验
        if (StringUtils.isBlank(request.getPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码不能为空");
        }
        // 昵称非空校验
        if (StringUtils.isBlank(request.getNickname())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "昵称不能为空");
        }
    }

    /**
     * 验证码校验
     * <p>
     * 校验规则：
     * 1. Redis中存在该账号的验证码；
     * 2. 请求的验证码与Redis中的验证码一致。
     *
     * @param account 接收验证码的账号（邮箱/手机号）
     * @param code    请求的验证码
     * @throws BusinessException 抛出场景：验证码不存在/不匹配 → ErrorCode.VERIFICATION_CODE_ERROR。
     */
    private void validateVerificationCode(String account, String code) {
        String redisCode = stringRedisTemplate.opsForValue().get(account);
        if (StringUtils.isBlank(redisCode) || !redisCode.equals(code)) {
            throw new BusinessException(ErrorCode.VERIFICATION_CODE_ERROR);
        }
    }

    /**
     * 检查用户是否已存在（仅支持邮箱注册）
     * <p>
     * 核心逻辑：
     * 1. 仅处理邮箱账号，非邮箱直接抛出参数错误；
     * 2. 使用LambdaQueryWrapper查询邮箱是否已注册；
     * 3. 使用exists方法替代getOne，避免多数据报错/空Wrapper查全表。
     *
     * @param account 待校验的账号（邮箱）
     * @throws BusinessException 抛出场景：
     *                           - 非邮箱账号 → ErrorCode.PARAMS_ERROR（仅支持邮箱注册）；
     *                           - 账号已存在 → ErrorCode.USER_ALREADY_EXISTS。
     */
    private void checkUserExistence(String account) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        // 仅支持邮箱注册，非邮箱直接拦截
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            queryWrapper.eq(User::getEmail, account);
        } else {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持邮箱注册");
        }
        // 检查账号是否已存在
        boolean exists = this.exists(queryWrapper);
        if (exists) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
    }

    /**
     * 创建用户实体（邮箱注册专用）
     * <p>
     * 核心逻辑：
     * 1. 生成雪花算法用户ID；
     * 2. 赋值昵称、邮箱（手机号置空避免非空报错）；
     * 3. 密码加密：MD5(盐值+明文密码)。
     *
     * @param request 注册请求参数
     * @return User 组装好的用户实体
     */
    private User createUser(RegisterRequest request) {
        User user = new User();
        // 雪花算法生成唯一用户ID
        user.setUserId(IdUtil.getSnowflake().nextId());
        // 赋值昵称
        user.setNickname(request.getNickname());

        // 邮箱注册：赋值邮箱，手机号置空（避免数据库非空约束报错）
        if (request.getAccount().matches(UserConstant.EMAIL_REGEX)) {
            user.setEmail(request.getAccount());
            user.setPhone("");
        }

        // 密码加密：盐值+明文密码 MD5加密
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + request.getPassword()).getBytes());
        user.setPassword(encryptedPassword);

        return user;
    }

    /**
     * 保存用户并生成Token（并发安全处理）
     * <p>
     * 核心逻辑：
     * 1. synchronized锁（account.intern()）：使用字符串常量池锁，避免不同对象的相同账号并发问题；
     * 2. 保存用户实体 + 初始化用户统计信息；
     * 3. 删除Redis中的验证码；
     * 4. 生成JWT令牌并存储到Redis；
     * 5. 组装登录响应VO并返回。
     *
     * @param user    待保存的用户实体
     * @param account 注册账号（邮箱）
     * @return LoginResponse 登录响应VO
     * @throws BusinessException 抛出场景：保存用户/统计信息失败 → ErrorCode.SYSTEM_ERROR（用户注册失败）。
     */
    private LoginResponse saveUserAndGenerateToken(User user, String account) {
        // 并发安全锁：避免同一账号多次注册
        synchronized (account.intern()) {
            // 1. 保存用户实体
            boolean saveSuccess = this.save(user);
            // 2. 初始化用户统计信息（粉丝数、关注数等默认0）
            UserStats stats = new UserStats();
            stats.setUserId(user.getUserId());
            boolean saveStatsSuccess = userStatsService.save(stats);

            // 校验保存结果，失败则抛出系统异常
            if (!saveSuccess || !saveStatsSuccess) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户注册失败");
            }

            // 3. 删除Redis中的验证码，避免重复使用
            stringRedisTemplate.delete(account);

            // 4. 生成JWT令牌并存储到Redis（过期时间JWT_TIME_OUT天）
            String token = JwtUtil.generate(user.getUserId().toString());
            stringRedisTemplate.opsForValue().set(user.getUserId().toString(), token, JWTConstant.JWT_TIME_OUT, TimeUnit.DAYS);

            // 5. 组装登录响应VO（用户信息+统计信息+Token）
            LoginResponse response = new LoginResponse();
            BeanUtil.copyProperties(user, response);
            BeanUtil.copyProperties(stats, response);
            response.setToken(token);

            return response;
        }
    }

    /**
     * 校验账号格式
     * <p>
     * 校验规则：账号必须是有效的邮箱或手机号。
     *
     * @param account 待校验的账号
     * @throws BusinessException 抛出场景：账号格式错误 → ErrorCode.PARAMS_ERROR（含具体错误信息）。
     */
    private void validateAccountFormat(String account) {
        if (!account.matches(UserConstant.PHONE_REGEX) && !account.matches(UserConstant.EMAIL_REGEX)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号必须是有效的手机号或邮箱");
        }
    }

    /**
     * 根据账号查询用户
     * <p>
     * 核心逻辑：
     * 1. 按账号类型（邮箱/手机号）构建查询条件；
     * 2. 查询并返回用户实体，不存在则抛出异常。
     *
     * @param account 待查询的账号（邮箱/手机号）
     * @return User 查询到的用户实体
     * @throws BusinessException 抛出场景：用户不存在 → ErrorCode.USER_NOT_EXISTS。
     */
    private User getCurrentUser(String account) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        // 按账号类型构建查询条件
        if (account.matches(UserConstant.EMAIL_REGEX)) {
            queryWrapper.eq(User::getEmail, account);
        } else if (account.matches(UserConstant.PHONE_REGEX)) {
            queryWrapper.eq(User::getPhone, account);
        }

        // 查询用户
        User user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_EXISTS);
        }

        return user;
    }
}