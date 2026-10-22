package com.orangecode.tianmu.consumer;

import com.alibaba.fastjson.JSON;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消费者
 */
@Component
@Slf4j
@RocketMQMessageListener(topic = "tianmu-topic", consumerGroup = "tianmuorange-consumer-group")
public class RocketMQConsumer implements RocketMQListener<String> {

    @Resource
    private BulletService bulletService;

    @Override
    public void onMessage(String message) {
        SendBulletRequest event = JSON.parseObject(message, SendBulletRequest.class);
        // Persistence is idempotent. Throwing lets RocketMQ retry failures instead of acknowledging data loss.
        bulletService.saveBulletToMySQL(event);
    }
}

