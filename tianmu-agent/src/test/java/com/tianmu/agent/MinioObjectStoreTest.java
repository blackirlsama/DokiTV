package com.tianmu.agent;

import com.sun.net.httpserver.*;
import com.tianmu.agent.adapters.storage.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.store.ObjectStore;
import io.minio.MinioClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK against a local S3 protocol fixture; does not claim a deployed MinIO integration. */
class MinioObjectStoreTest {
    HttpServer server;
    MinioObjectStore store;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    final AtomicInteger requests = new AtomicInteger();
    volatile boolean bucketExists, unavailable;
    volatile String authorization;
    final String bucket = "tianmu-agent-artifacts", prefix = "agent-artifacts/";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        var client = MinioClient.builder().endpoint(endpoint()).credentials("fixture-access", "fixture-private-secret")
                .region("us-east-1").build();
        store = new MinioObjectStore(client, bucket, prefix);
        store.initialize();
    }
    @AfterEach void stop() { server.stop(0); }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void handle(HttpExchange exchange) throws java.io.IOException {
        requests.incrementAndGet(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
        exchange.getResponseHeaders().set("x-amz-request-id", "fixture-request");
        if (unavailable) { error(exchange, 403, "AccessDenied"); return; }
        if (path.equals("/" + bucket)) {
            if (method.equals("GET") && Arrays.asList("location", "location=").contains(exchange.getRequestURI().getRawQuery())) {
                byte[] body = "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); return;
            }
            if (method.equals("HEAD")) { exchange.sendResponseHeaders(bucketExists ? 200 : 404, -1); exchange.close(); return; }
            if (method.equals("PUT")) { bucketExists = true; exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        }
        String key = path.substring(("/" + bucket + "/").length());
        if (method.equals("PUT")) {
            if (objects.containsKey(key) && "*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) { error(exchange, 412, "PreconditionFailed"); return; }
            objects.put(key, exchange.getRequestBody().readAllBytes());
            contentTypes.put(key, exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
            exchange.sendResponseHeaders(200, -1);
        } else if (method.equals("GET")) {
            byte[] body = objects.get(key);
            if (body == null) { error(exchange, 404, "NoSuchKey"); return; }
            exchange.getResponseHeaders().set("Content-Type", contentTypes.getOrDefault(key, "application/octet-stream"));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } else if (method.equals("DELETE")) {
            objects.remove(key); contentTypes.remove(key); exchange.sendResponseHeaders(204, -1);
        } else { error(exchange, 400, "InvalidRequest"); return; }
        exchange.close();
    }
    void error(HttpExchange exchange, int status, String code) throws java.io.IOException {
        byte[] body = ("<Error><Code>" + code + "</Code><Message>private-internal-detail</Message><Resource>/fixture</Resource><RequestId>fixture-request</RequestId></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
    }
    @Test void reportAndEvidenceUseTheSamePrivateBucketAndCorrectContentTypes() {
        byte[] html = "<html>弹幕报告</html>".getBytes(StandardCharsets.UTF_8), json = "{}".getBytes(StandardCharsets.UTF_8);
        store.put("rp_report.html", html); store.put("ar_evidence.json", json);
        assertArrayEquals(html, store.get("rp_report.html", html.length, FileObjectStore.hash(html)));
        assertArrayEquals(json, store.get("ar_evidence.json", json.length, FileObjectStore.hash(json)));
        assertEquals(Set.of(prefix + "rp_report.html", prefix + "ar_evidence.json"), objects.keySet());
        assertEquals("text/html; charset=utf-8", contentTypes.get(prefix + "rp_report.html"));
        assertEquals("application/json", contentTypes.get(prefix + "ar_evidence.json"));
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 "));
        assertFalse(authorization.contains("fixture-private-secret"));
        store.delete("rp_report.html"); store.delete("rp_report.html");
        assertFalse(objects.containsKey(prefix + "rp_report.html"));
    }
    @Test void existingObjectsCannotBeOverwrittenAndReadsDetectTamperingOrUnexpectedLength() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        store.put("ar_immutable.json", body);
        assertEquals("OBJECT_STORE_UNAVAILABLE", assertThrows(AgentException.class, () -> store.put("ar_immutable.json", new byte[]{9})).code());
        assertArrayEquals(body, store.get("ar_immutable.json", body.length, FileObjectStore.hash(body)));
        objects.put(prefix + "ar_immutable.json", new byte[]{0, 1});
        assertEquals("ARTIFACT_UNAVAILABLE", assertThrows(AgentException.class, () -> store.get("ar_immutable.json", body.length, FileObjectStore.hash(body))).code());
        objects.put(prefix + "ar_immutable.json", new byte[1048577]);
        assertThrows(AgentException.class, () -> store.get("ar_immutable.json", body.length, FileObjectStore.hash(body)));
    }
    @Test void unsafeKeysAndUnboundedBodiesAreRejectedBeforeNetworkRequests() {
        int before = requests.get();
        for (String key : List.of("../escape.html", "/rp_report.html", "folder/rp_report.html", "rp_report.js"))
            assertThrows(AgentException.class, () -> store.put(key, new byte[0]));
        assertEquals("ARTIFACT_TOO_LARGE", assertThrows(AgentException.class, () -> store.put("rp_report.html", new byte[1048577])).code());
        assertThrows(AgentException.class, () -> store.get("ar_evidence.json", 1048577, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new MinioObjectStore(MinioClient.builder().endpoint(endpoint()).build(), bucket, "../escape/"));
        assertEquals(before, requests.get());
    }
    @Test void readinessChecksReadWriteAndDeleteAndFailuresDoNotLeakStorageDetails() {
        assertTrue(bucketExists); store.verifyReady(); assertTrue(objects.isEmpty());
        unavailable = true;
        AgentException failure = assertThrows(AgentException.class, () -> store.put("rp_report.html", new byte[]{1}));
        assertEquals("OBJECT_STORE_UNAVAILABLE", failure.code());
        assertFalse(failure.toString().contains("private-internal-detail"));
        assertNull(failure.getCause());
        assertThrows(AgentException.class, store::verifyReady);
    }
    @Test void configurationSelectsMinioAndLocalDevelopmentCanExplicitlyUseFiles(@TempDir Path directory) {
        new ApplicationContextRunner().withUserConfiguration(ObjectStorageConfiguration.class).withPropertyValues(
                "agent.storage.mode=mysql", "agent.storage.object-store=minio", "agent.storage.minio.endpoint=" + endpoint(),
                "agent.storage.minio.access-key=fixture-access", "agent.storage.minio.secret-key=fixture-private-secret")
                .run(context -> { assertNull(context.getStartupFailure()); assertInstanceOf(MinioObjectStore.class, context.getBean(ObjectStore.class)); });
        new ApplicationContextRunner().withUserConfiguration(ObjectStorageConfiguration.class).withPropertyValues(
                "agent.storage.mode=mysql", "agent.storage.object-store=file", "agent.storage.object-directory=" + directory)
                .run(context -> { assertNull(context.getStartupFailure()); assertInstanceOf(FileObjectStore.class, context.getBean(ObjectStore.class)); });
        new ApplicationContextRunner().withUserConfiguration(ObjectStorageConfiguration.class)
                .run(context -> assertTrue(context.getBeansOfType(ObjectStore.class).isEmpty()));
    }
    @Test void persistentConfigurationReusesPlatformMinioAndSupportsExplicitAgentOverrides() throws Exception {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader().load("persistent",
                new org.springframework.core.io.ClassPathResource("application-persistent.yml")).get(0);
        var runner = new ApplicationContextRunner().withUserConfiguration(ObjectStorageConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(source))
                .withPropertyValues("TIANMU_MINIO_URL=" + endpoint(), "TIANMU_MINIO_ACCESS_KEY=fixture-access", "TIANMU_MINIO_SECRET_KEY=fixture-private-secret");
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            context.getBean(ObjectStore.class).put("rp_shared.html", new byte[]{1});
            assertTrue(objects.containsKey(prefix + "rp_shared.html"));
        });
        runner.withPropertyValues("TIANMU_MINIO_URL=http://127.0.0.1:1", "AGENT_MINIO_URL=" + endpoint(), "AGENT_MINIO_PREFIX=isolated/")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    context.getBean(ObjectStore.class).put("rp_override.html", new byte[]{2});
                    assertTrue(objects.containsKey("isolated/rp_override.html"));
                });
    }
}
