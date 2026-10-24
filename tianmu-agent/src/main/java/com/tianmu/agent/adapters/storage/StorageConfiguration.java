package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.runtime.control.LoopPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Duration;

@Configuration
@EnableScheduling
public class StorageConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="agent.storage.mode", havingValue="mysql")
    HikariDataSource agentDataSource(@Value("${agent.storage.jdbc-url:}") String url,
            @Value("${agent.storage.username:}") String username, @Value("${agent.storage.password:}") String password) {
        if (!url.startsWith("jdbc:mysql:") || username.isBlank() || url.matches("(?is).*([?&])(password|user)=.*"))
            throw new IllegalArgumentException("Configure an Agent-only MySQL URL and username; credentials belong in separate properties");
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(username); config.setPassword(password);
        config.setMaximumPoolSize(8); config.setMinimumIdle(1); config.setConnectionTimeout(2500); config.setValidationTimeout(1000);
        config.setConnectionInitSql("SET time_zone = '+00:00'"); config.setPoolName("agent-storage");
        config.addDataSourceProperty("connectTimeout", "2000"); config.addDataSourceProperty("socketTimeout", "5000");
        return new HikariDataSource(config);
    }
    @Bean @ConditionalOnProperty(name="agent.storage.mode", havingValue="mysql")
    Flyway agentMigrations(HikariDataSource source) {
        Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false).load(); flyway.migrate(); return flyway;
    }
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="agent.storage.mode", havingValue="mysql")
    RedisMemoryCache memoryCache(ObjectMapper mapper, @Value("${agent.storage.redis.enabled:false}") boolean enabled,
            @Value("${agent.storage.redis.host:127.0.0.1}") String host, @Value("${agent.storage.redis.port:6379}") int port,
            @Value("${agent.storage.redis.password:}") String password) {
        return new RedisMemoryCache(mapper, enabled, host, port, password);
    }
    @Bean(destroyMethod="close") @ConditionalOnProperty(name="agent.storage.mode", havingValue="mysql")
    MysqlRunRepository persistentRuns(HikariDataSource source, Flyway migrations, ObjectMapper mapper,
            RedisMemoryCache cache, com.tianmu.agent.runtime.store.ObjectStore objects, LoopPolicy policy) {
        return new MysqlRunRepository(source, mapper, cache, objects, policy.requestTimeout(), policy.maxModelCalls());
    }
}
