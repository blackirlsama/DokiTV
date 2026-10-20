package com.dokitv.agent.harness.trace;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Single-process durable outbox. Stable files are replayed after restart until acknowledged. */
public final class TraceSpool implements AutoCloseable {
    private final Path directory;
    private final int maxFiles;
    private final long maxBytes;
    private final int maxRecordBytes;
    private final NavigableMap<String, Path> pending = new TreeMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private long bytes;
    private int files;
    private long sequence;

    public TraceSpool(Path root, int maxFiles, long maxBytes, int maxRecordBytes) throws IOException {
        this.directory = root.toAbsolutePath().normalize().resolve("pending");
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
        this.maxRecordBytes = maxRecordBytes;
        if (maxFiles < 1 || maxRecordBytes < 1024 || maxBytes < maxRecordBytes) throw new IllegalArgumentException("Invalid spool limits");
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException e) { lockChannel.close(); throw e; }
        if (acquired == null) { lockChannel.close(); throw new IOException("Trace spool already has an owner"); }
        lock = acquired;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".pending") || name.endsWith(".corrupt") || name.endsWith(".tmp")) {
                    bytes += Files.size(path);
                    files++;
                    if (name.endsWith(".pending")) pending.put(name, path);
                }
            }
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    public synchronized void append(String json) throws IOException {
        byte[] payload = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (payload.length > maxRecordBytes || files >= maxFiles || bytes + payload.length > maxBytes)
            throw new IOException("Trace spool capacity exceeded");
        String key = String.format(Locale.ROOT, "%013d-%020d-%s", System.currentTimeMillis(), sequence++, UUID.randomUUID());
        Path temporary = directory.resolve(key + ".tmp");
        Path stable = directory.resolve(key + ".pending");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(payload);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: do not present a partially committed record as exportable.
            Files.move(temporary, stable, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        pending.put(stable.getFileName().toString(), stable);
        bytes += payload.length;
        files++;
    }
    public synchronized Path oldest() { return pending.isEmpty() ? null : pending.firstEntry().getValue(); }
    public synchronized String read(Path path) throws IOException {
        requirePending(path);
        if (Files.size(path) > maxRecordBytes) throw new IOException("Oversized trace record");
        return Files.readString(path);
    }
    public synchronized void acknowledge(Path path) throws IOException {
        requirePending(path);
        long size = Files.size(path);
        Files.delete(path);
        pending.remove(path.getFileName().toString());
        bytes -= size;
        files--;
    }
    public synchronized void quarantine(Path path) throws IOException {
        requirePending(path);
        Files.move(path, path.resolveSibling(path.getFileName().toString().replace(".pending", ".corrupt")), StandardCopyOption.ATOMIC_MOVE);
        pending.remove(path.getFileName().toString());
        // Corrupt files still consume capacity and are preserved for operator inspection.
    }
    private void requirePending(Path path) {
        if (!path.equals(pending.get(path.getFileName().toString()))) throw new IllegalArgumentException("Unknown spool entry");
    }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized long usedBytes() { return bytes; }
    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { lockChannel.close(); }
    }
}
