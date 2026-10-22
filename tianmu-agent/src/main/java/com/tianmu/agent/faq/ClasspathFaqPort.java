package com.tianmu.agent.faq;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.FaqPort;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Demo-only exact FAQ matches. Production adapters still own visibility and approval rules. */
@Component
@Profile("demo & !business")
public class ClasspathFaqPort implements FaqPort {
    public record Entry(String id, String version, List<String> questions, String text) {}
    private final Map<String, FaqAnswer> entries;
    private final String allowedTenant;
    public ClasspathFaqPort() { this(new ClassPathResource("faq/demo.json")); }
    public ClasspathFaqPort(Resource resource) {
        this(resource, "demo");
    }
    /** Null tenant is for a public capabilities FAQ after authentication; no business facts are stored here. */
    public ClasspathFaqPort(Resource resource, String allowedTenant) {
        this.allowedTenant = allowedTenant;
        Map<String, FaqAnswer> loaded = new HashMap<>(); Set<String> ids = new HashSet<>();
        for (Entry entry : ResourceFiles.json(resource, Entry[].class)) {
            FaqAnswer answer = new FaqAnswer(entry.id(), entry.version(), entry.text());
            if (!ids.add(entry.id()) || entry.questions() == null || entry.questions().isEmpty()) throw new IllegalStateException("Invalid FAQ entry");
            for (String query : entry.questions()) {
                if (query == null || query.isBlank() || query.length() > 2000 || loaded.putIfAbsent(query.strip(), answer) != null)
                    throw new IllegalStateException("Invalid or duplicate FAQ question");
            }
        }
        if (loaded.isEmpty()) throw new IllegalStateException("FAQ catalog is empty");
        entries = Map.copyOf(loaded);
    }
    public Optional<FaqAnswer> find(Input input, CallControl control) {
        control.checkpoint();
        if (allowedTenant != null && !allowedTenant.equals(input.scope().tenantId())) throw new AgentException("FORBIDDEN", "FAQ is unavailable for this tenant");
        return Optional.ofNullable(entries.get(input.query().strip()));
    }
}
