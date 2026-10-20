package com.dokitv.agent.api;

import com.dokitv.agent.access.IdentityPort;
import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.runtime.*;
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
    public record CreateRun(@NotBlank @Size(max = 2000) String message) {}
    private final RunService runs;
    private final IdentityPort identity;
    public AgentController(RunService runs, IdentityPort identity) { this.runs = runs; this.identity = identity; }
    private AccessScope access(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new AgentException("UNAUTHORIZED", "Bearer authentication is required");
        return identity.authenticate(authorization.substring(7));
    }
    @PostMapping
    public ResponseEntity<Snapshot> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                           @RequestBody @Valid CreateRun input) {
        RunState run = runs.create(input.message(), access(auth));
        return ResponseEntity.accepted().location(URI.create("/api/agent/runs/" + run.id)).body(run.snapshot());
    }
    @GetMapping("/{id}")
    public Snapshot get(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return runs.owned(id, access(auth)).snapshot();
    }
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long after) {
        return runs.owned(id, access(auth)).subscribe(after);
    }
    @PostMapping("/{id}/cancel")
    public Snapshot cancel(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        RunState run = runs.owned(id, access(auth));
        run.cancel();
        return run.snapshot();
    }
    @GetMapping("/{id}/evidence/{evidenceId}")
    public Evidence evidence(@PathVariable UUID id, @PathVariable String evidenceId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        RunState run = runs.owned(id, access(auth));
        Evidence evidence = run.evidence.get(evidenceId);
        if (evidence == null) throw new AgentException("RESULT_NOT_FOUND", "Evidence not found");
        return evidence;
    }
}
