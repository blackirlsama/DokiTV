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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path detailDirectory;
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
    @Test void modelTraceCapturesActualMessagesProviderResponseAndReportedUsageWithoutCredentials() throws Exception {
        var exported = new CopyOnWriteArrayList<io.opentelemetry.sdk.trace.data.SpanData>();
        var exporter = new io.opentelemetry.sdk.trace.export.SpanExporter() {
            public io.opentelemetry.sdk.common.CompletableResultCode export(Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) { exported.addAll(spans); return flush(); }
            public io.opentelemetry.sdk.common.CompletableResultCode flush() { return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess(); }
            public io.opentelemetry.sdk.common.CompletableResultCode shutdown() { return flush(); }
        };
        var details = new com.tianmu.agent.harness.trace.TraceDetails(new com.tianmu.agent.adapters.storage.FileObjectStore(detailDirectory), mapper, 131072, true);
        UUID run = UUID.randomUUID();
        try (var provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var span = provider.get("test").spanBuilder("agent.model").startSpan();
            try (var scope = details.bind(io.opentelemetry.context.Context.root().with(span), run).makeCurrent()) {
                new DeepSeekAdapters().deepSeekHarnessModel(client, properties, mapper, new PromptCatalog()).decide(context(), control());
            } finally { span.end(); }
            var attributes = exported.get(0).getAttributes();
            assertEquals(15L, attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("model.total_tokens")));
            assertEquals("stop", attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("model.finish_reason")));
            assertEquals("NOT_APPLICABLE", attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("model.first_token_state")));
            for (String direction : List.of("input", "provider_output")) {
                String[] ref = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("detail." + direction + "_ref")).split("/");
                String body = new String(details.read(run, ref[0], ref[1], Long.parseLong(ref[2]), ref[3]), StandardCharsets.UTF_8);
                assertFalse(body.contains("test-secret"));
                assertEquals(direction.equals("input") ? "MODEL_REQUEST" : "MODEL_PROVIDER_RESPONSE", mapper.readTree(body).path("kind").asText());
            }
        }
    }
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
    String understood(String query) {
        return "{\"needsClarification\":false,\"clarificationQuestion\":null,\"conditions\":{\"video_id\":1001,\"date\":\"2026-10-20\",\"metric\":\"bullet_count\"},\"rewrittenQuery\":\"" + query + "\"}";
    }
    @Test void singleUnderstandingCallParsesClarificationConditionsAndRewrite() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        answer = request -> understood(input().query());
        Understanding result = port.understand(input(), control());
        assertFalse(result.needsClarification());
        assertEquals(input().query(), result.rewrittenQuery());
        assertEquals(1001, result.conditions().get("video_id"));
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).toString().contains("private-user"));
        answer = request -> "{\"needsClarification\":true,\"clarificationQuestion\":\"Which date?\",\"conditions\":{},\"rewrittenQuery\":null}";
        assertEquals("Which date?", port.understand(input(), control()).clarificationQuestion());
    }
    @Test void understandingRejectsCoercionExtraFieldsAndContradictoryPayloads() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        for (String invalid : List.of(
                understood("query").replace("\"needsClarification\":false", "\"needsClarification\":\"false\""),
                understood("query").replace("\"video_id\":1001", "\"video_id\":{\"nested\":1001}"),
                understood("query").replace("\"clarificationQuestion\":null", "\"clarificationQuestion\":\"Which date?\""),
                understood("query").replace("\"needsClarification\":false", "\"needsClarification\":true")
                        .replace("\"clarificationQuestion\":null", "\"clarificationQuestion\":\"Which date?\""),
                understood("query").replace("\"rewrittenQuery\":\"query\"", "\"rewrittenQuery\":7"),
                understood("query").replace("\"conditions\":{", "\"extra\":true,\"conditions\":{"))) {
            answer = request -> invalid;
            assertThrows(IllegalArgumentException.class, () -> port.understand(input(), control()));
        }
    }
    @Test void intentUsesIndependentStructuredModelRequestAndRejectsUnknownFields() {
        var port = new DeepSeekAdapters().deepSeekPreparation(client, properties, mapper, new PromptCatalog());
        answer = request -> "{\"intent\":\"VIDEO_METRIC\"}";
        assertEquals(Intent.VIDEO_METRIC, port.intent(input(), control()));
        assertEquals(properties.preparationModel(), requests.get(0).path("model").asText());
        assertFalse(requests.get(0).toString().contains("private-tenant"));
        assertFalse(requests.get(0).toString().contains("private-user"));
        for (String invalid : List.of("{\"intent\":\"ADMIN\"}", "{\"intent\":\"OTHER\",\"extra\":true}", "{\"intent\":7}")) {
            answer = request -> invalid;
            assertThrows(IllegalArgumentException.class, () -> port.intent(input(), control()));
        }
    }

    @Test void persistedPreparationWithoutIntentRemainsReadable() throws Exception {
        PreparedRequest old = mapper.readValue("""
                {"originalQuery":"q","query":"q","emotion":"UNKNOWN","degradedStages":[],"skippedStages":[]}
                """, PreparedRequest.class);
        assertEquals(Intent.UNKNOWN, old.intent());
        assertEquals("q", old.query());
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
