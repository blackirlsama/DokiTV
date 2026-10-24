package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.*;
import com.tianmu.agent.faq.ClasspathFaqPort;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.*;
import java.time.*;
import org.springframework.core.io.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CatalogArchitectureTest {
    Resource text(String value) { return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)); }
    Resource skill(String name, String tool) {
        return new ByteArrayResource(("{\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"description\":\"test skill\",\"tools\":[\"" + tool + "\"]}").getBytes(StandardCharsets.UTF_8)) {
            @Override public Resource createRelative(String path) { assertEquals("SKILL.md", path); return text("Skill instructions for " + name); }
        };
    }
    Resource[] append(Resource[] original, Resource item) {
        List<Resource> all = new ArrayList<>(Arrays.asList(original)); all.add(item); return all.toArray(Resource[]::new);
    }
    List<AgentTool> builtins() {
        return new ArrayList<>(List.of(new ListSkillsTool(), new ReadSkillTool(), new ReadResultTool(), new QueryDanmakuTool((q, s, c) -> { throw new AssertionError("Unexpected live query"); }), new CreateDanmakuReportTool(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
                new QueryVideoMetricsTool((q, s) -> { fail("Query must not run"); return null; })));
    }
    @Test void newSkillAndToolCanRegisterWithoutChangingDispatcher() {
        SkillCatalog skills = new SkillCatalog(append(ResourceFiles.discover("classpath*:skills/*/skill.json"), skill("echo-analysis", "echo")));
        ToolCatalog catalog = new ToolCatalog(append(ResourceFiles.discover("classpath*:tools/*/tool.json"), text("""
                {"name":"echo","description":"Echo a diagnostic value","inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}}
                """)));
        List<AgentTool> handlers = builtins();
        handlers.add(new AgentTool() {
            public String name() { return "echo"; }
            public ToolResult execute(ToolCall call, ToolContext context) { return new ToolResult(name(), Map.of("value", ToolArguments.text(call, "value")), null); }
        });
        ToolRegistry registry = new ToolRegistry(handlers, catalog, skills);
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            ToolDispatcher dispatcher = new ToolDispatcher(skills, fixture.harness, registry);
            RunState run = fixture.run(); run.start();
            assertTrue(dispatcher.definitions(Set.of()).stream().noneMatch(t -> t.name().equals("echo")));
            ToolCall echo = new ToolCall("echo", Map.of("value", "hello"));
            assertEquals("SKILL_REQUIRED", assertThrows(AgentException.class, () -> dispatcher.execute(echo, run)).code());
            dispatcher.execute(new ToolCall("read_skill", Map.of("name", "echo-analysis")), run);
            assertTrue(dispatcher.definitions(run.execution().activeSkills()).stream().anyMatch(t -> t.name().equals("echo")));
            assertEquals("hello", dispatcher.execute(echo, run).data().get("value"));
            assertEquals("INVALID_ARGUMENTS", assertThrows(AgentException.class, () -> dispatcher.execute(new ToolCall("echo", Map.of("value", "hello", "tenant", "fake")), run)).code());
            run.cancel();
        }
    }
    @Test void missingOrDuplicateHandlersAndUnknownSkillReferencesFailAtStartup() {
        ToolCatalog catalog = new ToolCatalog(); SkillCatalog skills = new SkillCatalog();
        List<AgentTool> handlers = builtins(); handlers.remove(0);
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(handlers, catalog, skills));
        List<AgentTool> duplicate = builtins(); duplicate.add(new ListSkillsTool());
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(duplicate, catalog, skills));
        SkillCatalog unknown = new SkillCatalog(append(ResourceFiles.discover("classpath*:skills/*/skill.json"), skill("unknown", "missing_tool")));
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(builtins(), catalog, unknown));
    }
    @Test void duplicateSkillNamesAndUnsupportedSchemasFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new SkillCatalog(new Resource[]{skill("same", "a"), skill("same", "b")}));
        Resource unsupported = text("""
                {"name":"bad","description":"bad","inputSchema":{"type":"object","properties":{"nested":{"type":"array"}},"required":["nested"],"additionalProperties":false}}
                """);
        assertThrows(IllegalStateException.class, () -> new ToolCatalog(new Resource[]{unsupported}));
        assertThrows(IllegalStateException.class, () -> new ToolCatalog(new Resource[]{text("{\"name\":\"a\",\"name\":\"b\"}")}));
    }
    @Test void skillResourcesAreImmutableStartupSnapshots() {
        AtomicReference<String> body = new AtomicReference<>("first instructions");
        Resource metadata = new ByteArrayResource("{\"name\":\"snapshot\",\"version\":\"1.0.0\",\"description\":\"test\",\"tools\":[\"echo\"]}".getBytes(StandardCharsets.UTF_8)) {
            @Override public Resource createRelative(String path) { return text(body.get()); }
        };
        SkillCatalog catalog = new SkillCatalog(new Resource[]{metadata}); body.set("changed");
        assertEquals("first instructions", catalog.read("snapshot").instructions());
        assertThrows(UnsupportedOperationException.class, () -> catalog.read("snapshot").tools().add("other"));
    }
    ResourceLoader promptLoader(String index, Map<String, Resource> documents) {
        return new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                if (location.equals("classpath:prompts/index.json")) return text(index);
                return documents.getOrDefault(location, new ClassPathResource("missing-resource-for-test"));
            }
        };
    }
    @Test void missingDuplicateAndTraversalPromptEntriesAreRejected() {
        String entry = "{\"id\":\"test\",\"version\":\"1.0.0\",\"path\":\"test.md\"}";
        assertThrows(IllegalStateException.class, () -> new PromptCatalog(promptLoader("[" + entry + "]", Map.of())));
        assertThrows(IllegalStateException.class, () -> new PromptCatalog(promptLoader("[" + entry + "," + entry + "]", Map.of("classpath:prompts/test.md", text("prompt")))));
        assertThrows(IllegalStateException.class, () -> new PromptCatalog(promptLoader("[{\"id\":\"test\",\"version\":\"1.0.0\",\"path\":\"../private.md\"}]", Map.of())));
    }
    @Test void promptRegistryIsImmutableAndAllRuntimePromptsArePackaged() {
        PromptCatalog catalog = new PromptCatalog();
        assertEquals(Set.of("common.json-output", "common.current-focus-boundary", "preparation.emotion", "preparation.intent", "preparation.understanding", "harness.system", "harness.finalize"), catalog.ids());
        assertTrue(catalog.text("harness.finalize").contains("Do not call tools"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.ids().clear());
        assertThrows(IllegalStateException.class, () -> catalog.text("not-registered"));
    }
    @Test void oversizedAndInvalidUtf8DocumentsAreRejectedBeforeParsing() {
        assertThrows(IllegalStateException.class, () -> ResourceFiles.text(text("x".repeat(100)), 10));
        assertThrows(IllegalStateException.class, () -> ResourceFiles.text(new ByteArrayResource(new byte[]{(byte) 0xff}), 10));
    }
    @Test void faqResourceKeepsApprovedIdentityAndDoesNotMatchMetrics() {
        ClasspathFaqPort faq = new ClasspathFaqPort(); CallControl control = new CallControl(Instant.now().plusSeconds(1), new CancellationToken());
        var owner = new AccessScope("demo", "alice", Set.of(1001L));
        assertEquals("demo-capabilities", faq.find(new Input("你能做什么", LocalDate.now(), owner), control).orElseThrow().id());
        assertTrue(faq.find(new Input("查询视频1001昨天的弹幕数", LocalDate.now(), owner), control).isEmpty());
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> faq.find(new Input("你能做什么", LocalDate.now(), new AccessScope("other", "alice", Set.of(1001L))), control)).code());
    }
    @Test void ambiguousFaqQuestionsAreRejectedAtStartup() {
        Resource duplicate = text("""
                [{"id":"help","version":"1","questions":["hello","hello"],"text":"help"}]
                """);
        assertThrows(IllegalStateException.class, () -> new ClasspathFaqPort(duplicate));
    }
}
