package com.tianmu.agent.resource;

import com.tianmu.agent.core.AgentException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offsets count Unicode code points; pages never split a surrogate pair. */
public final class TextPage {
    private TextPage() {}
    public static Map<String, Object> read(String text, int offset, int limit) {
        int total = text.codePointCount(0, text.length());
        if (offset < 0 || offset > total || limit < 1 || limit > 4096)
            throw new AgentException("INVALID_ARGUMENTS", "Page offset or limit is outside allowed bounds");
        int end = offset + Math.min(limit, total - offset);
        int startIndex = text.offsetByCodePoints(0, offset);
        int endIndex = text.offsetByCodePoints(startIndex, end - offset);
        return Map.of("content", text.substring(startIndex, endIndex), "offset", offset,
                "next_offset", end, "total_chars", total, "has_more", end < total, "sha256", hash(text));
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
