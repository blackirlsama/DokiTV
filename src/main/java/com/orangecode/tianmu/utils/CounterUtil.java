package com.orangecode.tianmu.utils;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.IntegerCodec;
import org.springframework.stereotype.Service;

/**
 * 计数工具类，提供基于Redis的计数器功能
 * 使用Redisson实现分布式计数器，支持按时间粒度进行计数
 */
@Slf4j
@Service
public class CounterUtil {

    // 注入Redisson客户端，用于操作Redis
    @Resource
    private RedissonClient redissonClient;
    



    /**
     * 增加并返回计数
     * 根据指定的时间粒度生成Redis键，实现按时间间隔的计数功能
     *
     * @param key                     缓存键
     * @param timeInterval            时间间隔
     * @param timeUnit                时间间隔单位（秒、分钟、小时）
     * @param expirationTimeInSeconds 计数器缓存过期时间（秒）
     * @return long 返回当前计数器的值
     */
    public long incrAndGetCounter(String key, int timeInterval, TimeUnit timeUnit, long expirationTimeInSeconds) {
        // 检查key是否为空
        if (StrUtil.isBlank(key)) {
            return 0;
        }

        // 根据时间粒度生成 Redis Key
        long timeFactor;
        switch (timeUnit) {
            // 秒级时间因子计算
            case SECONDS:
                timeFactor = Instant.now().getEpochSecond() / timeInterval;
                break;
            // 分钟级时间因子计算
            case MINUTES:
                timeFactor = Instant.now().getEpochSecond() / timeInterval / 60;
                break;
            // 小时级时间因子计算
            case HOURS:
                timeFactor = Instant.now().getEpochSecond() / timeInterval / 3600;
                break;
            // 不支持的时间单位
            default:
                throw new IllegalArgumentException("不支持的单位");
        }

        // 组合生成最终的Redis键，包含原始键和时间因子
        String redisKey = key + ":" + timeFactor;

        // Lua 脚本，实现原子性计数操作
        // 如果键已存在，则递增计数
        // 如果键不存在，则设置初始值为1，并设置过期时间
        String luaScript =
                "if redis.call('exists', KEYS[1]) == 1 then " +
                        "  return redis.call('incr', KEYS[1]); " +
                        "else " +
                        "  redis.call('set', KEYS[1], 1); " +
                        "  redis.call('expire', KEYS[1], ARGV[1]); " +
                        "  return 1; " +
                        "end";

        // 执行 Lua 脚本
        RScript script = redissonClient.getScript(IntegerCodec.INSTANCE);
        Object countObj = script.eval(
                RScript.Mode.READ_WRITE,
                luaScript,
                RScript.ReturnType.INTEGER,
                Collections.singletonList(redisKey), expirationTimeInSeconds
        );
        return (long) countObj;
    }
}