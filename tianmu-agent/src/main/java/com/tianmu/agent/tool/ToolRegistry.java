package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.ToolDefinition;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.skill.SkillCatalog;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> handlers;
    private final Map<String, ToolDefinition> definitions;
    private final SkillCatalog skills;
    public ToolRegistry(List<AgentTool> tools, ToolCatalog catalog, SkillCatalog skills) {
        this.skills = skills;
        Map<String, AgentTool> loaded = new LinkedHashMap<>();
        for (AgentTool tool : tools)
            if (tool == null || !ResourceFiles.name(tool.name())) throw new IllegalStateException("Invalid tool handler");
        tools.stream().sorted(Comparator.comparingInt(AgentTool::order).thenComparing(AgentTool::name)).forEach(tool -> {
            if (!ResourceFiles.name(tool.name()) || loaded.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalStateException("Invalid or duplicate tool handler");
        });
        if (!loaded.keySet().equals(catalog.all().keySet())) throw new IllegalStateException("Tool handlers and resource descriptors do not match");
        for (String name : skills.names())
            if (!loaded.keySet().containsAll(skills.read(name).tools())) throw new IllegalStateException("Skill references an unknown tool: " + name);
        for (AgentTool tool : loaded.values())
            if (tool.requiresSkill() && skills.names().stream().noneMatch(name -> skills.read(name).tools().contains(tool.name())))
                throw new IllegalStateException("Business tool is not declared by any skill: " + tool.name());
        Map<String, ToolDefinition> described = new LinkedHashMap<>();
        loaded.forEach((name, tool) -> {
            ToolDefinition definition = tool.describe(catalog.all().get(name), skills);
            ToolCatalog.validate(definition);
            if (!name.equals(definition.name())) throw new IllegalStateException("Handler cannot rename a tool descriptor");
            described.put(name, definition);
        });
        handlers = Collections.unmodifiableMap(loaded); definitions = Collections.unmodifiableMap(described);
    }
    public AgentTool get(String name) {
        AgentTool tool = handlers.get(name);
        if (tool == null) throw new AgentException("TOOL_NOT_FOUND", "Tool is not registered");
        return tool;
    }
    public ToolDefinition definition(String name) { get(name); return definitions.get(name); }
    public List<ToolDefinition> definitions(Set<String> activeSkills) {
        Set<String> active = new HashSet<>();
        activeSkills.forEach(name -> active.addAll(skills.read(name).tools()));
        return handlers.values().stream().filter(tool -> !tool.requiresSkill() || active.contains(tool.name()))
                .map(tool -> definitions.get(tool.name())).toList();
    }
}
