package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.store.ObjectStore;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** Private objects in the existing MinIO instance; public URLs never enter Agent responses. */
public final class MinioObjectStore implements ObjectStore {
    private static final int MAX_BYTES = 1048576;
    private final MinioClient client;
    private final String bucket;
    private final String prefix;

    public MinioObjectStore(MinioClient client, String bucket, String prefix) {
        if (client == null || bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || prefix == null || prefix.length() > 128
                || !prefix.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/"))
            throw new IllegalArgumentException("Configure an Agent bucket and a bounded object prefix");
        this.client = client; this.bucket = bucket; this.prefix = prefix;
    }

    public static MinioObjectStore connect(String endpoint, String accessKey, String secretKey, String bucket, String prefix) {
        try {
            URI uri = URI.create(endpoint);
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank())
                throw new IllegalArgumentException();
            var http = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
            MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
                    .httpClient(http).build();
            MinioObjectStore store = new MinioObjectStore(client, bucket, prefix);
            store.initialize();
            return store;
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Configure Agent MinIO using the existing TIANMU_MINIO_* settings or AGENT_MINIO_* overrides"); }
    }

    /** A new bucket has MinIO's default private policy; existing bucket policies are not changed. */
    public void initialize() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception e) { throw unavailable(); }
    }

    private String object(String key) { ObjectKeys.validate(key); return prefix + key; }
    private static AgentException unavailable() { return new AgentException("OBJECT_STORE_UNAVAILABLE", "Private object storage is unavailable"); }

    @Override public void put(String key, byte[] body) {
        String object = object(key);
        if (body.length > MAX_BYTES) throw new AgentException("ARTIFACT_TOO_LARGE", "Artifact exceeds 1 MiB");
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(object)
                    .stream(new ByteArrayInputStream(body), body.length, -1)
                    .contentType(key.endsWith(".html") ? "text/html; charset=utf-8" : "application/json")
                    .extraHeaders(Map.of("If-None-Match", "*")).build());
        } catch (Exception e) { throw unavailable(); }
    }

    @Override public byte[] get(String key, long size, String sha) {
        String object = object(key);
        if (size < 0 || size > MAX_BYTES || sha == null || !sha.matches("[a-f0-9]{64}"))
            throw new AgentException("ARTIFACT_UNAVAILABLE", "Artifact is missing or inconsistent");
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())) {
            byte[] body = stream.readNBytes((int) size + 1);
            if (body.length != size || !FileObjectStore.hash(body).equals(sha)) throw new java.io.IOException("Integrity mismatch");
            return body;
        } catch (Exception e) { throw new AgentException("ARTIFACT_UNAVAILABLE", "Artifact is missing or inconsistent"); }
    }

    @Override public void delete(String key) {
        String object = object(key);
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(object).build()); }
        catch (Exception e) { throw unavailable(); }
    }

    @Override public void verifyReady() {
        String key = "probe_" + UUID.randomUUID() + ".json";
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { put(key, body); get(key, body.length, FileObjectStore.hash(body)); }
        finally { delete(key); }
    }
}
