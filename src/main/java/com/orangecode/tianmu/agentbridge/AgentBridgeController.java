package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@ConditionalOnProperty(name="agent.bridge.enabled", havingValue="true")
@RequestMapping("/internal/agent")
public final class AgentBridgeController {
    public record Resources(Set<Long> videoIds) {}
    private final BusinessAuthentication authentication;
    private final BusinessMetrics metrics;
    public AgentBridgeController(BusinessAuthentication authentication, BusinessMetrics metrics) {
        this.authentication = authentication; this.metrics = metrics;
    }
    @GetMapping("/principal")
    public BusinessAuthentication.Principal principal(@RequestHeader(value="Authorization", required=false) String auth) {
        return authentication.authenticate(auth);
    }
    @PostMapping("/authorize")
    public Resources authorize(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Resources input) {
        var principal = authentication.authenticate(auth);
        return new Resources(metrics.allowed(principal, input.videoIds()));
    }
    @GetMapping("/metrics")
    public BusinessMetrics.Metric query(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestParam long videoId, @RequestParam LocalDate date, @RequestParam String metric) {
        var principal = authentication.authenticate(auth);
        return metrics.query(principal, videoId, date, metric);
    }
    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<Map<String, String>> failure(BridgeException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.code()));
    }
}
