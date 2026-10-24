package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.AgentException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.io.IOException;
import java.util.HexFormat;

/** Private immutable local object adapter. Only server-generated opaque keys reach this class. */
public final class FileObjectStore implements com.tianmu.agent.runtime.store.ObjectStore {
    private final Path root;
    public FileObjectStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try { Files.createDirectories(this.root); if (Files.isSymbolicLink(this.root)) throw new IOException("Symlink root"); }
        catch (IOException e) { throw new AgentException("OBJECT_STORE_UNAVAILABLE", "Private object directory cannot be opened"); }
    }
    private Path path(String key) {
        ObjectKeys.validate(key);
        Path value = root.resolve(key).normalize();
        if (!value.startsWith(root) || Files.isSymbolicLink(value)) throw new AgentException("FORBIDDEN", "Invalid object path");
        return value;
    }
    @Override public void verifyReady() {
        Path probe = null;
        try {
            probe = Files.createTempFile(root, ".readiness-", ".tmp");
            Files.write(probe, new byte[]{1}, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new AgentException("OBJECT_STORE_UNAVAILABLE", "Private object destination is unavailable");
        } finally {
            if (probe != null) try { Files.deleteIfExists(probe); }
            catch (IOException e) { throw new AgentException("OBJECT_STORE_UNAVAILABLE", "Private object probe cleanup failed"); }
        }
    }
    public void put(String key, byte[] body) {
        if (body.length > 1048576) throw new AgentException("ARTIFACT_TOO_LARGE", "Artifact exceeds 1 MiB");
        try { Files.write(path(key), body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (IOException e) { throw new AgentException("OBJECT_STORE_UNAVAILABLE", "Artifact write failed"); }
    }
    public byte[] get(String key, long size, String sha) {
        try {
            Path file = path(key); if (Files.size(file) != size || size > 1048576) throw new IOException("Size mismatch");
            byte[] body = Files.readAllBytes(file); if (!hash(body).equals(sha)) throw new IOException("Digest mismatch"); return body;
        } catch (IOException e) { throw new AgentException("ARTIFACT_UNAVAILABLE", "Artifact is missing or inconsistent"); }
    }
    public void delete(String key) { try { Files.deleteIfExists(path(key)); } catch (IOException e) { throw new AgentException("OBJECT_STORE_UNAVAILABLE", "Artifact cleanup failed"); } }
    public static String hash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
