package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent/runs/{runId}/reports")
public final class ReportController {
    private final IdentityPort identity; private final RunRepository reports;
    public ReportController(IdentityPort identity, RunRepository reports) { this.identity = identity; this.reports = reports; }
    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> report(@PathVariable UUID runId, @PathVariable String artifactId,
            @RequestHeader(value="Authorization", required=false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication required");
        var owner = identity.forRun(auth.substring(7), runId);
        byte[] body = reports.report(runId, owner, artifactId);
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
}
