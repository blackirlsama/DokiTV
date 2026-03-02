package com.orangecode.tianmu.producer;

import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 生产者
 */
@Component
public class RocketMQProducer {

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    public void sendMessage(String topic, String message) {

        rocketMQTemplate.convertAndSend(topic, message);
    }
}