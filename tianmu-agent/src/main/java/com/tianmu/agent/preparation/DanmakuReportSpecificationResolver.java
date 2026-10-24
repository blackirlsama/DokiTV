package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import com.tianmu.agent.core.PreparationTypes.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class DanmakuReportSpecificationResolver implements TaskSpecificationResolver, QueryReadinessPolicy {
    public Optional<Specification> resolve(String text, LocalDate date) {
        if (!DanmakuReportTask.requested(text)) return Optional.empty();
        try { return Optional.of(DanmakuReportTask.specification(DanmakuReportTask.parse(text, date))); }
        catch (IllegalArgumentException | DateTimeException e) { return Optional.empty(); }
    }
    public Optional<Clarification> validate(Input input) {
        if (!DanmakuReportTask.requested(input.query())) return Optional.empty();
        try { DanmakuReportTask.parse(input.query(), input.businessDate()).checkTime(Instant.now()); return Optional.empty(); }
        catch (RuntimeException e) { return Optional.of(new Clarification(true, "请明确videoId和最近30天内已结束的时间范围（最长24小时），例如：分析视频1001昨天的弹幕并生成报告；直播间ID也使用videoId。")); }
    }
}
