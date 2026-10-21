package com.tianmu.agent.runtime.control;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(LoopPolicy.class)
public class LoopConfiguration {
    @Bean(destroyMethod = "close") LoopControl loopControl(LoopPolicy policy) { return new LoopControl(policy); }
}
