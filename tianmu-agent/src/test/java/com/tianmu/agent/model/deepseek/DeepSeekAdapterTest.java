package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekAdapterTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    HttpServer server;
    DeepSeekProperties properties;
    DeepSeekClient client;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    Function<JsonNode, String> answer = input -> "{\"kind\":\"CLARIFY\",\"clarification\":\"Date?\"}";
    int status = 200;
    CountDownLatch entered, release;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes()); requests.add(request);
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (entered != null) entered.countDown();
            if (release != null) try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", answer.apply(request)))), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
            try { exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start();
        properties = new DeepSeekProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-secret",
                "deepseek-flash", "deepseek-v4-pro", Duration.ofMillis(300), 384, 1536);
        client = new DeepSeekClient(properties, mapper);
    }
    @AfterEach void stop() { if (release != null) release.countDown(); server.stop(0); }
    CallControl control() { return new CallControl(Instant.now().plusSeconds(2), new CancellationToken()); }
    Input input() { return new Input("视频1001昨天的弹幕数", LocalDate.of(2026, 10, 21), new AccessScope("private-tenant", "private-user", Set.of(1001L))); }
    ModelContext context() { return new ModelContext(input().query(), input().businessDate(), input().scope(), Set.of(), List.of(), List.of(), 6); }
    @Test void oneKeySelectsTwoModelsAndDoesNotSendIdentityScope() {
        answer = request -> request.path("model").asText().equals("deepseek-flash") ? "{\"emotion\":\"NEUTRAL\"}"
                : "{\"kind\":\"CLARIFY\",\"clarification\":\"Date?\"}";
        var adapters = new DeepSeekAdapters();
        assertEquals(Emotion.NEUTRAL, adapters.deepSeekPreparation(client, properties, mapper, new PromptCatalog()).emotion(input(), control()));
        assertEquals(Kind.CLARIFY, adapters.deepSeekHarnessModel(client, properties, mapper, new PromptCatalog()).decide(context(), control()).kind());
        assertEquals(List.of("Bearer test-secret", "Bearer test-secret"), auth);
        assertEquals("deepseek-flash", requests.get(0).path("model").asText());
        assertEquals("deepseek-v4-pro", requests.get(1).path("model").asText());
        for (JsonNode request : requests) {
            assertEquals("disabled", request.path("thinking").path("type").asText());
            assertEquals("json_object", request.path("response_format").path("type").asText());
            assertFalse(request.toString().contains("private-tenant")); assertFalse(request.toString().contains("private-user"));
        }
        assertFalse(properties.toString().contains("test-secret"));
    }
    @Test void structuredPreparationFlagsAndRewriteAreParsedWithoutBooleanCoercion() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        answer = request -> "{\"required\":false,\"question\":null}";
        assertFalse(port.clarify(input(), control()).required());
        answer = request -> "{\"required\":\"false\",\"question\":null}";
        assertThrows(IllegalArgumentException.class, () -> port.clarify(input(), control()));
        answer = request -> "{\"query\":\"视频1001昨天的弹幕数\"}";
        assertEquals(input().query(), port.rewrite(input(), control()));
    }
    @Test void invalidDecisionAndProviderAuthFailureAreDistinctAndSanitized() {
        var port = new DeepSeekAdapters().deepSeekHarnessModel(client, properties, mapper, new PromptCatalog());
        answer = request -> "{\"kind\":\"ANSWER\",\"answer\":{\"text\":\"unsupported\",\"evidenceIds\":[3]}}";
        assertEquals("INVALID_DECISION", assertThrows(AgentException.class, () -> port.decide(context(), control())).code());
        status = 401; answer = request -> "PROVIDER_SECRET_DETAIL";
        AgentException error = assertThrows(AgentException.class, () -> port.decide(context(), control()));
        assertEquals("MODEL_AUTH_FAILED", error.code()); assertFalse(error.getMessage().contains("PROVIDER_SECRET_DETAIL"));
    }
    @Test void oversizedAndDuplicateKeyResponsesAreRejected() {
        answer = request -> "x".repeat(140000);
        assertThrows(IllegalStateException.class, () -> client.complete(properties.harnessModel(), List.of(Map.of("role", "user", "content", "JSON")), 128, control()));
        answer = request -> "{\"query\":\"a\",\"query\":\"b\"}";
        assertThrows(IllegalStateException.class, () -> client.complete(properties.harnessModel(), List.of(Map.of("role", "user", "content", "JSON")), 128, control()));
    }
    @Test void cancellationCancelsActiveHttpRequest() throws Exception {
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        CallControl control = control(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> client.complete(properties.harnessModel(), List.of(Map.of("role", "user", "content", "JSON")), 128, control));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); control.cancellation().cancel(StopReason.USER_CANCELLED);
            ExecutionException error = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertInstanceOf(AgentException.class, error.getCause());
            assertEquals("CANCELLED", ((AgentException) error.getCause()).code());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
