package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.adapters.storage.MysqlRunRepository;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.RunService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent")
public class StorageController {
    private final RunService runs; private final IdentityPort identity; private final ObjectProvider<MysqlRunRepository> storage;
    public StorageController(RunService runs, IdentityPort identity, ObjectProvider<MysqlRunRepository> storage) { this.runs = runs; this.identity = identity; this.storage = storage; }
    private AccessScope access(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication is required");
        return identity.authenticate(auth.substring(7));
    }
    private MysqlRunRepository persistent() {
        var repository = storage.getIfAvailable();
        if (repository == null) throw new AgentException("STORAGE_REQUIRED", "Enable persistent storage for this endpoint"); return repository;
    }
    @GetMapping("/sessions/{id}/messages")
    public List<MemoryTypes.Message> messages(@PathVariable UUID id, @RequestHeader(value="Authorization", required=false) String auth,
            @RequestParam(defaultValue="9223372036854775807") long before) { return runs.messages(id, access(auth), before); }
    @GetMapping("/runs/{id}/artifacts")
    public List<Map<String, Object>> artifacts(@PathVariable UUID id, @RequestHeader(value="Authorization", required=false) String auth) {
        AccessScope owner = access(auth); return persistent().artifacts(id, owner);
    }
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestHeader(value="Authorization", required=false) String auth) {
        AccessScope owner = access(auth); persistent().deleteSession(id, owner); return ResponseEntity.accepted().build();
    }
    @GetMapping("/runs/{id}/artifacts/{artifact}")
    public ResponseEntity<byte[]> artifact(@PathVariable UUID id, @PathVariable String artifact, @RequestHeader(value="Authorization", required=false) String auth) {
        AccessScope owner = access(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header("X-Content-Type-Options", "nosniff").body(persistent().artifact(id, artifact, owner));
    }
}
