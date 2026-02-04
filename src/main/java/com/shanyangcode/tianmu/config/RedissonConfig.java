package com.shanyangcode.tianmu.config;

import com.shanyangcode.tianmu.common.ErrorCode;
import com.shanyangcode.tianmu.exception.ThrowUtils;
import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "data.redis")
@Data
public class RedissonConfig {

    private String host;
    private Integer port = 6379;
    private Integer database = 0;
    private String password = ""; // 空字符串，适配无密码Redis
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