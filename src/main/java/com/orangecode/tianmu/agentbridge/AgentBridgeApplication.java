package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import java.util.Map;

/** Optional small business-side deployment; uses the same production authentication and metric components. */
@Configuration(proxyBeanMethods=false)
@Profile("agent-bridge-standalone")
@EnableAutoConfiguration
@Import({AgentBridgeConfiguration.class, AgentBridgeController.class})
public class AgentBridgeApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AgentBridgeApplication.class);
        application.setAdditionalProfiles("agent-bridge-standalone");
        application.setDefaultProperties(Map.of("spring.config.name", "application-agent-bridge"));
        application.run(args);
    }
}
