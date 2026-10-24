package com.tianmu.agent.preparation;

import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic first-version grammar; ambiguous ranges are clarified, never inferred by a model. */
public final class DanmakuReportTask {
    private DanmakuReportTask() {}
    public static boolean requested(String text) { return text.contains("弹幕") && (text.contains("报告") || text.contains("分析")); }
    public static Query parse(String text, LocalDate date) {
        var ids = DanmakuTarget.matcher(text).results().toList();
        if (ids.size() != 1) throw new IllegalArgumentException("请明确一个videoId，例如视频1001或直播间1001。");
        var times = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2})?").matcher(text).results().map(m -> m.group().replace(' ', 'T')).toList();
        LocalDateTime start, end;
        if (times.size() == 2) { start = LocalDateTime.parse(times.get(0)); end = LocalDateTime.parse(times.get(1)); }
        else if (!times.isEmpty()) throw new IllegalArgumentException("请提供完整的开始和结束时间，例如2026-10-23 20:00到2026-10-23 22:00。");
        else {
            var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text).results().map(m -> m.group()).toList();
            if (dates.size() == 1 && !text.contains("昨天")) start = LocalDate.parse(dates.get(0)).atStartOfDay();
            else if (dates.isEmpty() && text.contains("昨天")) start = date.minusDays(1).atStartOfDay();
            else throw new IllegalArgumentException("请指定一天，或提供完整的开始和结束日期时间。");
            // Do not silently discard a bare clock interval attached to a day.
            if (Pattern.compile("\\d{1,2}:\\d{2}|\\d{1,2}点").matcher(text).find())
                throw new IllegalArgumentException("请用完整日期时间描述区间，例如2026-10-23 20:00到2026-10-23 22:00。");
            end = start.plusDays(1);
        }
        return new Query(Long.parseLong(ids.get(0).group(1)), start, end);
    }
    public static Specification specification(Query query) {
        Map<String, Object> conditions = Map.of("video_id", query.videoId(), "start", query.start().toString(), "end", query.end().toString());
        return new Specification("danmaku.report", conditions, Map.of("video_id", "user_input", "start", "server_parsed", "end", "server_parsed"));
    }
}
