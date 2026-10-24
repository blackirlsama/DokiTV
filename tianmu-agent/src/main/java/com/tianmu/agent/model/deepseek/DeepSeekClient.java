package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import com.tianmu.agent.harness.trace.TraceDetails;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import io.opentelemetry.api.trace.Span;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.*;

/** Single shared credential, independently selected model IDs; no prompt/body/header logging or implicit retries. */
public final class DeepSeekClient {
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper) {
        this.config = config; this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public JsonNode complete(String model, List<Map<String, String>> messages, int maxTokens, CallControl control) {
        control.checkpoint();
        TraceAttributes.apply(Span.current(), Map.of("model.name", model, "model.provider", "deepseek", "model.max_tokens", maxTokens,
                "model.temperature", 0, "model.streaming", false, "model.first_token_state", "NOT_APPLICABLE", "model.message_count", messages.size(), "model.reasoning_state", "DISABLED"));
        long nanos = Duration.between(Instant.now(), control.deadline()).toNanos();
        if (nanos <= 0) throw unavailable();
        try {
            Map<String, Object> requestBody = Map.of("model", model, "messages", messages,
                    "thinking", Map.of("type", "disabled"), "response_format", Map.of("type", "json_object"),
                    "temperature", 0, "stream", false, "max_tokens", maxTokens);
            TraceDetails.capture("input", requestBody, "MODEL_REQUEST");
            byte[] payload = mapper.writeValueAsBytes(requestBody);
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofNanos(nanos)).header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            long requestStarted = System.nanoTime();
            CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new LimitedBody());
            try (var registration = control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response = future.get(Math.max(1, Duration.between(Instant.now(), control.deadline()).toNanos()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { future.cancel(true); throw unavailable(); }
                catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
                catch (CancellationException e) { control.checkpoint(); throw unavailable(); }
                control.checkpoint();
                TraceAttributes.apply(Span.current(), Map.of("model.http_status", response.statusCode(), "model.response_ms", Math.max(0, (System.nanoTime() - requestStarted) / 1_000_000)));
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new AgentException("MODEL_AUTH_FAILED", "Model provider authentication failed");
                if (response.statusCode() == 429 || response.statusCode() >= 500) throw unavailable();
                if (response.statusCode() != 200) throw new AgentException("MODEL_REQUEST_REJECTED", "Model provider rejected request");
                JsonNode body = mapper.readTree(response.body());
                TraceDetails.capture("provider_output", body, "MODEL_PROVIDER_RESPONSE");
                // Count provider-reported usage even if the generated JSON or finish reason is unusable.
                JsonNode usage = body.path("usage");
                com.tianmu.agent.harness.trace.TraceUsage.record(model, token(usage.path("prompt_tokens")), token(usage.path("completion_tokens")),
                        token(usage.path("prompt_cache_hit_tokens")), token(usage.path("prompt_cache_miss_tokens")), token(usage.path("completion_tokens_details").path("reasoning_tokens")));
                JsonNode choice = body.path("choices").path(0);
                if (choice.path("finish_reason").isTextual()) TraceAttributes.apply(Span.current(), Map.of("model.finish_reason", choice.path("finish_reason").asText()));
                if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
                JsonNode content = choice.path("message").path("content");
                if (!content.isTextual() || content.textValue().isBlank()) throw unavailable();
                JsonNode result = mapper.readTree(content.textValue());
                if (result == null || !result.isObject()) throw unavailable();
                return result;
            }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
    }
    private static Long token(JsonNode value) { return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null; }
    private IllegalStateException unavailable() { return new IllegalStateException("Model response unavailable"); }
    /** Cancel subscription before an oversized response can accumulate in memory. */
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 131072) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Model response too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
