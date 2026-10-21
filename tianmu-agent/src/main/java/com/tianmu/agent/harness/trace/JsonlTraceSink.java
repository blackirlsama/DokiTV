package com.tianmu.agent.harness.trace;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Bounded local archive used by default; not a production audit database. */
public final class JsonlTraceSink implements TraceSink {
    private final Path directory;
    private final long maxBytes;
    private final int backups;
    public JsonlTraceSink(Path directory, long maxBytes, int backups) throws IOException {
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.backups = backups;
        if (maxBytes < 1024 || backups < 1) throw new IllegalArgumentException("Invalid archive limits");
        Files.createDirectories(directory);
    }
    @Override public synchronized void send(TraceEnvelope envelope, String json) throws IOException {
        Path current = directory.resolve("spans.jsonl");
        byte[] bytes = (json + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Files.exists(current) && Files.size(current) + bytes.length > maxBytes) {
            for (int i = backups; i >= 1; i--) {
                Path destination = directory.resolve("spans." + i + ".jsonl");
                if (i == backups) Files.deleteIfExists(destination);
                Path source = i == 1 ? current : directory.resolve("spans." + (i - 1) + ".jsonl");
                if (Files.exists(source)) Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (FileChannel channel = FileChannel.open(current, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
