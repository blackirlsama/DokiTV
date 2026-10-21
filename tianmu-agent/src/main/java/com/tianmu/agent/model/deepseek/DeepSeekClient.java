package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
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
        long nanos = Duration.between(Instant.now(), control.deadline()).toNanos();
        if (nanos <= 0) throw unavailable();
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of("model", model, "messages", messages,
                    "thinking", Map.of("type", "disabled"), "response_format", Map.of("type", "json_object"),
                    "temperature", 0, "stream", false, "max_tokens", maxTokens));
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofNanos(nanos)).header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new LimitedBody());
            try (var registration = control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response = future.get(Math.max(1, Duration.between(Instant.now(), control.deadline()).toNanos()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { future.cancel(true); throw unavailable(); }
                catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
                catch (CancellationException e) { control.checkpoint(); throw unavailable(); }
                control.checkpoint();
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new AgentException("MODEL_AUTH_FAILED", "Model provider authentication failed");
                if (response.statusCode() == 429 || response.statusCode() >= 500) throw unavailable();
                if (response.statusCode() != 200) throw new AgentException("MODEL_REQUEST_REJECTED", "Model provider rejected request");
                JsonNode body = mapper.readTree(response.body());
                JsonNode choice = body.path("choices").path(0);
                if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
                JsonNode content = choice.path("message").path("content");
                if (!content.isTextual() || content.textValue().isBlank()) throw unavailable();
                JsonNode result = mapper.readTree(content.textValue());
                if (result == null || !result.isObject()) throw unavailable();
                TraceAttributes.apply(Span.current(), Map.of("model.name", model,
                        "model.input_tokens", Math.max(0, body.path("usage").path("prompt_tokens").asLong()),
                        "model.output_tokens", Math.max(0, body.path("usage").path("completion_tokens").asLong())));
                return result;
            }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
    }
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
