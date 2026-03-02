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
        SendBulletRequest sendBulletRequest = JSON.parseObject(message, SendBulletRequest.class);
        System.out.println("收到消息: " + message);
        Long bulletId = sendBulletRequest.getBulletId();
        Long videoId = sendBulletRequest.getVideoId();

        System.out.println("解析后的参数：");
        System.out.println("videoId: " + sendBulletRequest.getVideoId());
        System.out.println("userId: " + sendBulletRequest.getUserId());
        System.out.println("content: " + sendBulletRequest.getContent());
        System.out.println("playbackTime: " + sendBulletRequest.getPlaybackTime());
        System.out.println("color: " + sendBulletRequest.getColor()); // 重点看颜色字段！

        // 1. 校验弹幕是否已存在（逻辑保留）
        if (bulletService.bulletExists(bulletId)) {
            log.info("弹幕已存在，跳过保存，bulletId: {}", bulletId); // 补充日志
            return;
        }

        try {
            bulletService.saveBulletToMySQL(sendBulletRequest);
            // 2. 新增：保存成功日志（关键！）
            log.info("弹幕保存到MySQL成功，videoId: {}, bulletId: {}", videoId, bulletId);
        } catch (Exception e) {
            // 3. 优化：明确打印异常原因（比如主键冲突）
            log.error("保存到MySQL失败，videoId: {}, bulletId: {}, 原因: {}",
                    videoId, bulletId, e.getMessage(), e);
            // 注释掉抛异常（避免MQ重复消费，先定位问题）
            // throw new RuntimeException("MySQL保存失败", e);
        }
    }
}

