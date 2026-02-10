package com.shanyangcode.tianmu.consumer;

import com.alibaba.fastjson.JSON;
import com.shanyangcode.tianmu.model.dto.bullet.SendBulletRequest;
import com.shanyangcode.tianmu.service.BulletService;

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
@RocketMQMessageListener(topic = "zzz-topic", consumerGroup = "zzz-consumer-group")
public class RocketMQConsumer implements RocketMQListener<String> {

    @Resource
    private BulletService bulletService;

    @Override
    public void onMessage(String message) {
        SendBulletRequest sendBulletRequest = JSON.parseObject(message, SendBulletRequest.class);
        System.out.println("收到消息: " + message);

        if (bulletService.bulletExists(sendBulletRequest.getBulletId())) {
            return;
        }

        try {
            bulletService.saveBulletToMySQL(sendBulletRequest);
        } catch (Exception e) {
            log.error("保存到MySQL失败，消息ID: {}", sendBulletRequest.getBulletId(), e);
            throw new RuntimeException("MySQL保存失败", e);
        }
    }

}