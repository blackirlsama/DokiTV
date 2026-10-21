package com.tianmu.agent.skill;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.Skill;
import com.tianmu.agent.resource.ResourceFiles;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

@Component
public class SkillCatalog {
    public record Metadata(String name, String version, String description, Set<String> tools) {}
    private final Map<String, Skill> skills;
    public SkillCatalog() { this(ResourceFiles.discover("classpath*:skills/*/skill.json")); }
    public SkillCatalog(Resource[] resources) {
        Map<String, Skill> loaded = new TreeMap<>();
        for (Resource resource : resources) {
            Metadata metadata = ResourceFiles.json(resource, Metadata.class);
            if (!ResourceFiles.name(metadata.name()) || !ResourceFiles.version(metadata.version())
                    || metadata.description() == null || metadata.description().isBlank() || metadata.tools() == null
                    || metadata.tools().isEmpty() || metadata.tools().stream().anyMatch(name -> !ResourceFiles.name(name)))
                throw new IllegalStateException("Invalid skill metadata");
            try {
                String instructions = ResourceFiles.text(resource.createRelative("SKILL.md"), 65536).strip();
                Skill skill = new Skill(metadata.name(), metadata.version(), metadata.description(), instructions, metadata.tools());
                if (loaded.putIfAbsent(skill.name(), skill) != null) throw new IllegalStateException("Duplicate skill: " + skill.name());
            } catch (IOException e) { throw new IllegalStateException("Cannot locate skill instructions", e); }
        }
        if (loaded.isEmpty()) throw new IllegalStateException("Skill catalog is empty");
        skills = Collections.unmodifiableMap(loaded);
    }
    public Set<String> names() { return skills.keySet(); }
    public List<Map<String, String>> list() {
        return skills.values().stream().map(skill -> Map.of("name", skill.name(), "description", skill.description())).toList();
    }
    public Skill read(String name) {
        Skill skill = skills.get(name);
        if (skill == null) throw new AgentException("SKILL_NOT_FOUND", "Unknown skill");
        return skill;
    }
    public void checkTool(String tool, Set<String> activeSkills) {
        boolean allowed = activeSkills.stream().map(this::read).anyMatch(skill -> skill.tools().contains(tool));
        if (!allowed) throw new AgentException("SKILL_REQUIRED", "Read the relevant skill before calling its tool");
    }
}
