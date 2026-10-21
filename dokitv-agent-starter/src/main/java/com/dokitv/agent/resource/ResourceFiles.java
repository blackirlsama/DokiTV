package com.dokitv.agent.resource;

import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Small, immutable classpath documents. Resource streams also work inside executable JARs. */
public final class ResourceFiles {
    private ResourceFiles() {}
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static Resource[] discover(String pattern) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            if (resources.length == 0 || resources.length > 128) throw new IllegalStateException("Invalid resource catalog size: " + pattern);
            return resources;
        } catch (IOException e) { throw new IllegalStateException("Cannot discover resources: " + pattern, e); }
    }
    public static String text(Resource resource, int maxBytes) {
        try (InputStream stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length == 0 || bytes.length > maxBytes) throw new IOException("Invalid document size");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.indexOf('\0') >= 0) throw new IOException("Invalid document content");
            return text;
        } catch (IOException e) { throw new IllegalStateException("Cannot load resource: " + resource.getDescription(), e); }
    }
    public static <T> T json(Resource resource, Class<T> type) {
        try { return JSON.readValue(text(resource, 65536), type); }
        catch (IOException | IllegalArgumentException e) { throw new IllegalStateException("Invalid JSON resource: " + resource.getDescription(), e); }
    }
    public static boolean name(String name) { return name != null && name.matches("[a-z][a-z0-9_-]{0,63}"); }
    public static boolean version(String version) { return version != null && version.matches("\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?"); }
}
