package com.dokitv.agent.prompt;

import com.dokitv.agent.resource.ResourceFiles;
import org.springframework.core.io.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Startup snapshot: prompt changes are versioned resources and take effect after redeployment. */
@Component
public class PromptCatalog {
    public record Entry(String id, String version, String path) {}
    public record Prompt(String id, String version, String content) {}
    private final Map<String, Prompt> prompts;
    public PromptCatalog() { this(new DefaultResourceLoader()); }
    public PromptCatalog(ResourceLoader loader) {
        Entry[] entries = ResourceFiles.json(loader.getResource("classpath:prompts/index.json"), Entry[].class);
        if (entries.length == 0 || entries.length > 128) throw new IllegalStateException("Invalid prompt catalog size");
        Map<String, Prompt> loaded = new TreeMap<>();
        for (Entry entry : entries) {
            if (entry.id() == null || !entry.id().matches("[a-z][a-z0-9_.-]{0,80}") || !ResourceFiles.version(entry.version())
                    || entry.path() == null || !entry.path().matches("[a-z0-9][a-z0-9/-]*\\.md"))
                throw new IllegalStateException("Invalid prompt entry");
            String content = ResourceFiles.text(loader.getResource("classpath:prompts/" + entry.path()), 16384).strip();
            if (loaded.putIfAbsent(entry.id(), new Prompt(entry.id(), entry.version(), content)) != null)
                throw new IllegalStateException("Duplicate prompt: " + entry.id());
        }
        prompts = Collections.unmodifiableMap(loaded);
    }
    public Prompt get(String id) {
        Prompt prompt = prompts.get(id);
        if (prompt == null) throw new IllegalStateException("Prompt is not registered: " + id);
        return prompt;
    }
    public String text(String id) { return get(id).content(); }
    public Set<String> ids() { return prompts.keySet(); }
}
