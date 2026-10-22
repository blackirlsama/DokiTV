package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/agent/runs")
public class AgentController {
    public record CreateRun(@NotBlank @Size(max = 2000) String message, UUID sessionId, UUID replyToRunId) {
        public CreateRun(String message) { this(message, null, null); }
    }
    private final RunService runs;
    private final IdentityPort identity;
    private final SseEventStreams streams;
    public AgentController(RunService runs, IdentityPort identity, SseEventStreams streams) { this.runs = runs; this.identity = identity; this.streams = streams; }
    private AccessScope access(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new AgentException("UNAUTHORIZED", "Bearer authentication is required");
        return identity.authenticate(authorization.substring(7));
    }
    private String credential(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication is required");
        return auth.substring(7);
    }
    @PostMapping
    public ResponseEntity<Snapshot> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody @Valid CreateRun input) {
        RunState run = runs.create(input.message(), identity.forCreate(credential(auth), input.message(), input.replyToRunId()), input.sessionId(), key, input.replyToRunId());
        return ResponseEntity.accepted().location(URI.create("/api/agent/runs/" + run.id)).body(run.snapshot());
    }
    @GetMapping("/{id}")
    public Snapshot get(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return runs.owned(id, identity.forRun(credential(auth), id)).snapshot();
    }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long after) {
        return streams.subscribe(runs.owned(id, identity.forRun(credential(auth), id)), after);
    }
    @PostMapping("/{id}/cancel")
    public Snapshot cancel(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        RunState run = runs.owned(id, identity.forRun(credential(auth), id));
        run.cancel();
        return run.snapshot();
    }
    @GetMapping("/{id}/evidence/{evidenceId}")
    public Evidence evidence(@PathVariable UUID id, @PathVariable String evidenceId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        RunState run = runs.owned(id, identity.forRun(credential(auth), id));
        Evidence evidence = run.execution().evidence().get(evidenceId);
        if (evidence == null) throw new AgentException("RESULT_NOT_FOUND", "Evidence not found");
        return evidence;
    }
}
