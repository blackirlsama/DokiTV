package com.tianmu.agent.demo;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.query.BusinessQueryPort;
import org.springframework.context.annotation.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

/** Offline fixtures only. No tianmu database, remote credentials or LLM is used. */
@Configuration
@Profile("demo")
public class DemoAdapters {
    /** Offline fixtures for the small-model port. Replace all three methods in an integration profile. */
    @Bean @Profile("!deepseek") PreparationModelPort demoPreparationModel() {
        return new PreparationModelPort() {
            public Emotion emotion(Input input, CallControl control) {
                control.checkpoint();
                return input.query().contains("太差") || input.query().contains("生气") ? Emotion.NEGATIVE : Emotion.NEUTRAL;
            }
            public Clarification clarify(Input input, CallControl control) {
                control.checkpoint();
                String text = input.query();
                if (!Pattern.compile("视频\\s*\\d+").matcher(text).find() || !text.contains("弹幕"))
                    return new Clarification(true, "演示模式支持单视频弹幕数查询，请提供视频ID、日期和要查询的指标。");
                if (!text.contains("昨天") && !Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text).find())
                    return new Clarification(true, "请指定一个 YYYY-MM-DD 格式的日期，或使用“昨天”。");
                return new Clarification(false, null);
            }
            public String rewrite(Input input, CallControl control) {
                control.checkpoint();
                return input.query().strip().replaceAll("\\s+", " ");
            }
        };
    }
    @Bean IdentityPort demoIdentity() {
        return credential -> switch (credential == null ? "" : credential) {
            case "demo-alice" -> new AccessScope("demo", "alice", Set.of(1001L));
            case "demo-bob" -> new AccessScope("demo", "bob", Set.of(1002L));
            default -> throw new AgentException("UNAUTHORIZED", "Use Bearer demo-alice or demo-bob in local demo mode");
        };
    }
    @Bean BusinessQueryPort demoQuery() {
        return (query, scope) -> {
            if (!scope.tenantId().equals("demo") || !scope.videoIds().contains(query.videoId()))
                throw new AgentException("FORBIDDEN", "Video is outside your data scope");
            if (!query.metric().equals("bullet_count"))
                throw new AgentException("METRIC_UNSUPPORTED", "Unsupported metric");
            return new MetricData(query.videoId(), query.date(), "bullet_count", 128L,
                    "bullet-count-demo-v1", "synthetic-fixture", Instant.now(), "Asia/Shanghai", true, true);
        };
    }
    @Bean @Profile("!deepseek") ModelPort scriptedDemoModel() {
        return context -> {
            var id = Pattern.compile("视频\\s*(\\d+)").matcher(context.message());
            if (!id.find() || !context.message().contains("弹幕"))
                return Decision.clarify("演示模式仅支持查询单个视频的弹幕数。请试：查询视频1001昨天的弹幕数。");
            long videoId;
            try { videoId = Long.parseLong(id.group(1)); }
            catch (NumberFormatException e) { return Decision.clarify("请提供有效的视频ID。"); }
            LocalDate date;
            var explicitDate = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(context.message());
            try {
                if (explicitDate.find()) date = LocalDate.parse(explicitDate.group());
                else if (context.message().contains("昨天")) date = context.businessDate().minusDays(1);
                else return Decision.clarify("请指定一个 YYYY-MM-DD 格式的日期，或使用“昨天”。");
            } catch (DateTimeException e) { return Decision.clarify("日期无效，请使用 YYYY-MM-DD。"); }
            if (!context.activeSkills().contains("video-metrics"))
                return Decision.tool("read_skill", Map.of("name", "video-metrics"));
            Optional<ToolResult> metric = context.observations().stream()
                    .filter(r -> r.tool().equals("query_video_metrics")).findFirst();
            if (metric.isEmpty()) return Decision.tool("query_video_metrics", Map.of(
                    "video_id", videoId, "date", date.toString(), "metric", "bullet_count"));
            ToolResult result = metric.get();
            return Decision.answer("【模拟数据】视频 " + videoId + " 在 " + date
                    + " 的弹幕数为 " + result.data().get("value") + " 条。此结果仅用于验证基架，不代表真实业务数据。",
                    List.of(result.evidenceId()));
        };
    }
}
