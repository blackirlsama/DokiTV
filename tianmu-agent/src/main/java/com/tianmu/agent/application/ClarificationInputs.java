package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTarget;
import java.util.regex.Pattern;

/** Explicit resumption for the first supported capability, without inferring new privileges. */
public final class ClarificationInputs {
    private ClarificationInputs() {}
    public static String combine(String original, String reply) {
        return combine(original, reply, null);
    }
    public static String combine(String original, String reply, java.time.LocalDate originBusinessDate) {
        if (com.tianmu.agent.preparation.DanmakuReportTask.requested(original)) return combineReport(original, reply, originBusinessDate);
        if (!original.contains("弹幕")) throw new AgentException("RESUME_UNSUPPORTED", "Resubmit the original question with the missing conditions");
        String video = one(DanmakuTarget.ID_PATTERN, reply, 1);
        if (video == null) video = one(DanmakuTarget.ID_PATTERN, original, 1);
        String date = one("\\d{4}-\\d{2}-\\d{2}", reply, 0);
        if (date == null && reply.contains("昨天")) date = "昨天";
        if (date == null) date = one("\\d{4}-\\d{2}-\\d{2}", original, 0);
        if (date == null && original.contains("昨天")) date = originBusinessDate == null ? "昨天" : originBusinessDate.minusDays(1).toString();
        return "查询" + (video == null ? "" : "视频" + video) + (date == null ? "" : " " + date) + "的弹幕数";
    }
    public static java.util.Map<String, Object> confirmed(String query, java.time.LocalDate date) {
        if (com.tianmu.agent.preparation.DanmakuReportTask.requested(query)) {
            try { return com.tianmu.agent.preparation.DanmakuReportTask.specification(com.tianmu.agent.preparation.DanmakuReportTask.parse(query, date)).conditions(); }
            catch (RuntimeException ignored) { return java.util.Map.of(); }
        }
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        String video = unambiguous(DanmakuTarget.ID_PATTERN, query, 1), explicitDate = unambiguous("\\d{4}-\\d{2}-\\d{2}", query, 0);
        if (video != null) fields.put("video_id", video);
        if (explicitDate != null) {
            try { fields.put("date", java.time.LocalDate.parse(explicitDate).toString()); }
            catch (java.time.DateTimeException ignored) { /* Invalid dates remain missing conditions. */ }
        }
        else if (query.contains("昨天")) fields.put("date", date.minusDays(1).toString());
        if (query.contains("弹幕")) fields.put("metric", "bullet_count");
        return java.util.Map.copyOf(fields);
    }
    private static String combineReport(String original, String reply, java.time.LocalDate date) {
        String video = one(DanmakuTarget.ID_PATTERN, reply, 1);
        if (video == null) video = one(DanmakuTarget.ID_PATTERN, original, 1);
        String time = reply;
        if (!Pattern.compile("\\d{4}-\\d{2}-\\d{2}|昨天").matcher(reply).find()) time = original;
        if (time.contains("昨天") && date != null) time = time.replace("昨天", date.minusDays(1).toString());
        time = DanmakuTarget.matcher(time).replaceAll("");
        String combined = "分析视频" + (video == null ? "" : video) + " " + time + " 的弹幕并生成报告";
        try { com.tianmu.agent.preparation.DanmakuReportTask.parse(combined, date == null ? java.time.LocalDate.now(com.tianmu.agent.core.DanmakuTypes.ZONE) : date); }
        catch (RuntimeException e) { throw new AgentException("INVALID_REQUEST", "请重新提供完整的videoId和报告时间范围"); }
        return combined;
    }
    private static String unambiguous(String expression, String text, int group) {
        var matches = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(text).results().toList();
        return matches.size() == 1 ? matches.get(0).group(group) : null;
    }
    private static String one(String expression, String text, int group) {
        var matches = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(text).results().toList();
        if (matches.size() > 1) throw new AgentException("INVALID_REQUEST", "Provide exactly one video and date");
        return matches.isEmpty() ? null : matches.get(0).group(group);
    }
}
