package com.tianmu.agent.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Video and live-room names resolve to the same authorized videoId namespace. */
public final class DanmakuTarget {
    private DanmakuTarget() {}
    public static final String ID_PATTERN = "(?:(?:视频|直播间)(?:\\s*ID)?|(?<![A-Za-z0-9_])video_?id(?![A-Za-z_]))\\s*[:：=#]?\\s*(\\d+)(?![\\d.])";
    private static final Pattern IDS = Pattern.compile(ID_PATTERN, Pattern.CASE_INSENSITIVE);
    public static Matcher matcher(String text) { return IDS.matcher(text); }
}
