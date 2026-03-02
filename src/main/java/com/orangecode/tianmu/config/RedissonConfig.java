package com.orangecode.tianmu.config;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.ThrowUtils;
import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置类，用于配置和创建Redisson客户端实例
 * 通过@Configuration注解标记为配置类，通过@ConfigurationProperties注解绑定配置文件中的属性
 */
@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedissonConfig {

    // Redis服务器主机地址
    private String host;
    // Redis服务器端口，默认为6379
    private Integer port = 6379;
    // Redis数据库编号，默认为0
    private Integer database = 0;
    private String password = "e65K4t8w2"; // 空字符串，适配无密码Redis
    private Integer timeout = 2000;

    @Bean
    public RedissonClient redissonClient() {
        // 原有核心属性非空校验
        ThrowUtils.throwIf(host == null || host.trim().isEmpty(), ErrorCode.PARAMS_ERROR, "Redis地址未配置（data.redis.host）");
        ThrowUtils.throwIf(port == null || port <= 0 || port > 65535, ErrorCode.PARAMS_ERROR, "Redis端口配置非法（data.redis.port）");
        ThrowUtils.throwIf(database == null || database < 0 || database > 15, ErrorCode.PARAMS_ERROR, "Redis数据库编号非法（必须0-15）");

        // 构建Redisson配置
        Config config = new Config();
        // 单节点配置核心：仅当密码非空时，才设置密码（避免无密码时发送AUTH命令）
        var singleServerConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setTimeout(timeout);

        // 关键修复：密码非空（且非空白）时才执行认证
        if (password != null && !password.trim().isEmpty()) {
            singleServerConfig.setPassword(password);
        }

        return Redisson.create(config);
    }
}