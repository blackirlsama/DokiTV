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
    public record Metadata(String name, String version, String description, Set<String> tools, Map<String, String> resources) {}
    private final Map<String, Skill> skills;
    private final Map<String, Map<String, String>> documents;
    private final Map<String, String> fingerprints;
    public SkillCatalog() { this(ResourceFiles.discover("classpath*:skills/*/skill.json")); }
    public SkillCatalog(Resource[] resources) {
        Map<String, Skill> loaded = new TreeMap<>();
        Map<String, Map<String, String>> refs = new TreeMap<>();
        Map<String, String> hashes = new TreeMap<>();
        for (Resource resource : resources) {
            Metadata metadata = ResourceFiles.json(resource, Metadata.class);
            if (!ResourceFiles.name(metadata.name()) || !ResourceFiles.version(metadata.version())
                    || metadata.description() == null || metadata.description().isBlank() || metadata.description().length() > 512 || metadata.tools() == null
                    || metadata.tools().isEmpty() || metadata.tools().size() > 32 || metadata.tools().stream().anyMatch(name -> !ResourceFiles.name(name)))
                throw new IllegalStateException("Invalid skill metadata");
            try {
                String instructions = ResourceFiles.text(resource.createRelative("SKILL.md"), 16384).strip();
                Skill skill = new Skill(metadata.name(), metadata.version(), metadata.description(), instructions, metadata.tools());
                if (loaded.putIfAbsent(skill.name(), skill) != null) throw new IllegalStateException("Duplicate skill: " + skill.name());
                Map<String, String> declared = metadata.resources() == null ? Map.of() : metadata.resources();
                if (declared.size() > 16) throw new IllegalStateException("Too many skill references");
                Map<String, String> bodies = new TreeMap<>();
                int totalBytes = 0;
                for (var entry : declared.entrySet()) {
                    String path = entry.getValue();
                    if (!ResourceFiles.name(entry.getKey()) || path == null
                            || !path.matches("references/[A-Za-z0-9_/-]+\\.(md|txt|json)"))
                        throw new IllegalStateException("Invalid skill reference path");
                    String body = ResourceFiles.text(resource.createRelative(path), 262144);
                    totalBytes += body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if (totalBytes > 1048576) throw new IllegalStateException("Skill references exceed package limit");
                    bodies.put(entry.getKey(), body);
                }
                refs.put(skill.name(), Map.copyOf(bodies));
                var payload = Map.of("name", skill.name(), "version", skill.version(), "description", skill.description(),
                        "instructions", instructions, "tools", new TreeSet<>(skill.tools()),
                        "paths", new TreeMap<>(declared), "resources", bodies);
                String canonical = new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsString(payload);
                hashes.put(skill.name(), com.tianmu.agent.resource.TextPage.hash(canonical));
            } catch (IOException e) { throw new IllegalStateException("Cannot locate skill instructions", e); }
        }
        if (loaded.isEmpty()) throw new IllegalStateException("Skill catalog is empty");
        skills = Collections.unmodifiableMap(loaded);
        documents = Map.copyOf(refs);
        fingerprints = Map.copyOf(hashes);
    }
    public Set<String> names() { return skills.keySet(); }
    public List<Map<String, String>> list() {
        return skills.values().stream().map(skill -> Map.of("name", skill.name(), "version", skill.version(), "description", skill.description())).toList();
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
    public String fingerprint(String name) { read(name); return fingerprints.get(name); }
    public List<Map<String, Object>> resources(String name) {
        read(name);
        return documents.get(name).entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> Map.<String, Object>of(
                "name", e.getKey(), "total_chars", e.getValue().codePointCount(0, e.getValue().length()),
                "sha256", com.tianmu.agent.resource.TextPage.hash(e.getValue()))).toList();
    }
    public Map<String, Object> resource(String name, String reference, int offset, int limit) {
        read(name);
        String body = documents.get(name).get(reference);
        if (body == null) throw new AgentException("SKILL_RESOURCE_NOT_FOUND", "Reference is not declared by this skill");
        return com.tianmu.agent.resource.TextPage.read(body, offset, limit);
    }
}
