package com.dokitv.agent.application;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.core.TaskTypes.*;
import com.dokitv.agent.prompt.PromptCatalog;
import com.dokitv.agent.resource.ResourceFiles;
import com.dokitv.agent.runtime.RunState;
import com.dokitv.agent.skill.SkillCatalog;
import com.dokitv.agent.tool.ToolCatalog;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import com.dokitv.agent.preparation.TaskMeaning;

/** Freezes task meaning and resource versions once, after readiness checks and before the loop. */
@Component
public class TaskAssembler {
    private final Map<String, String> versions;
    private final Set<String> allowedTools;
    private final TaskMeaning meaning;
    public TaskAssembler(SkillCatalog skills, PromptCatalog prompts, ToolCatalog tools) { this(skills, prompts, tools, TaskMeaning.defaults()); }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskAssembler(SkillCatalog skills, PromptCatalog prompts, ToolCatalog tools, TaskMeaning meaning) {
        this.meaning = meaning;
        Map<String, String> loaded = new TreeMap<>();
        skills.names().forEach(name -> loaded.put("skill:" + name, skills.read(name).version()));
        prompts.ids().forEach(id -> loaded.put("prompt:" + id, prompts.get(id).version()));
        tools.all().forEach((name, definition) -> loaded.put("tool:" + name, fingerprint(definition)));
        versions = Map.copyOf(loaded); allowedTools = Set.copyOf(tools.all().keySet());
    }
    public PreparedTask assemble(RunState run) {
        run.checkpoint();
        var preparation = Objects.requireNonNull(run.preparedRequest());
        var specification = meaning.resolve(run.query(), run.businessDate);
        if (!run.query().equals(run.message)) {
            Map<String, String> provenance = new HashMap<>();
            specification.provenance().keySet().forEach(key -> provenance.put(key, "server_validated_clarification"));
            specification = new Specification(specification.type(), specification.conditions(), provenance);
        }
        return new PreparedTask(run.id, run.message, preparation.query(), run.businessDate, "Asia/Shanghai", run.owner,
                preparation, specification, versions, allowedTools, run.deadline(), run.modelCallLimit());
    }
    private String fingerprint(ToolDefinition definition) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(definition)));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint tool definition"); }
    }
}
