package com.tianmu.agent.demo;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTarget;
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
    /** Offline fixtures for three independent preparation branches. */
    @Bean @Profile("!deepseek") PreparationModelPort demoPreparationModel() {
        return new PreparationModelPort() {
            public Intent intent(Input input, CallControl control) {
                control.checkpoint();
                if (DanmakuReportTask.requested(input.query())) return Intent.DANMAKU_REPORT;
                return input.query().contains("弹幕") ? Intent.VIDEO_METRIC : Intent.UNKNOWN;
            }
            public Emotion emotion(Input input, CallControl control) {
                control.checkpoint();
                return input.query().contains("太差") || input.query().contains("生气") ? Emotion.NEGATIVE : Emotion.NEUTRAL;
            }
            public Understanding understand(Input input, CallControl control) {
                control.checkpoint();
                String text = input.query();
                if (DanmakuReportTask.requested(text)) {
                    try { return Understanding.proceed(DanmakuReportTask.specification(DanmakuReportTask.parse(text, input.businessDate())).conditions(), text); }
                    catch (RuntimeException e) { return Understanding.clarify("请提供videoId和日期，例如：分析视频1001昨天的弹幕并生成报告；直播间ID也使用videoId。"); }
                }
                if (!DanmakuTarget.matcher(text).find() || !text.contains("弹幕"))
                    return Understanding.clarify("请提供videoId、日期和要查询的弹幕指标；可使用视频ID或直播间ID。");
                if (!text.contains("昨天") && !Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text).find())
                    return Understanding.clarify("请指定一个 YYYY-MM-DD 格式的日期，或使用“昨天”。");
                return Understanding.proceed(TaskMeaning.defaults().resolve(text, input.businessDate()).conditions(),
                        text.strip().replaceAll("\\s+", " "));
            }
        };
    }
    @Bean @Profile("!business") IdentityPort demoIdentity() {
        return credential -> switch (credential == null ? "" : credential) {
            case "demo-alice" -> new AccessScope("demo", "alice", Set.of(1001L));
            case "demo-bob" -> new AccessScope("demo", "bob", Set.of(1002L));
            default -> throw new AgentException("UNAUTHORIZED", "Use Bearer demo-alice or demo-bob in local demo mode");
        };
    }
    @Bean @Profile("!business & !persistent") BusinessQueryPort demoQuery() {
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
            if (context.task() != null && context.task().specification().type().equals("danmaku.report")) {
                if (!context.activeSkills().contains("danmaku-analysis")) return Decision.tool("read_skill", Map.of("name", "danmaku-analysis"));
                var observation = context.observations().stream().filter(r -> r.tool().equals("query_danmaku")).findFirst();
                if (observation.isEmpty()) return Decision.tool("query_danmaku", context.task().specification().conditions());
                ToolResult value = observation.get();
                String sections = "[{\"title\":\"发货时效咨询\",\"observation\":\"抽样片段中多次出现关于发货时间的询问，这是内容线索，不代表全量弹幕中的占比。\",\"recommendation\":\"在讲解或置顶信息中补充预计发货时效。\",\"sample_ids\":[\"100000\"]},"
                        + "{\"title\":\"优惠领取咨询\",\"observation\":\"抽样片段中出现优惠券领取方式相关提问；不能据此判断全场观众的关注比例。\",\"recommendation\":\"说明优惠券入口、领取条件和使用期限。\",\"sample_ids\":[\"100001\"]}]";
                return Decision.tool("create_danmaku_report", Map.of("evidence_id", value.evidenceId(), "sections_json", sections));
            }
            var id = DanmakuTarget.matcher(context.message());
            if (!id.find() || !context.message().contains("弹幕"))
                return Decision.clarify("请提供单个videoId和日期，例如：查询视频1001昨天的弹幕数；直播间1001也使用相同标识。");
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
    @Bean @Profile("!business") com.tianmu.agent.query.DanmakuQueryPort demoDanmakuQuery() {
        return (query, scope, control) -> {
            control.checkpoint();
            if (!scope.videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "videoId is outside demo scope");
            var buckets = new ArrayList<com.tianmu.agent.core.DanmakuTypes.Bucket>();
            var samples = new ArrayList<com.tianmu.agent.core.DanmakuTypes.Sample>();
            String[] texts = {"这款什么时候发货？", "优惠券在哪里领取？", "主播讲得很清楚，材质也展示到了。", "刚进直播间，商品链接在哪？",
                    "有黑色和大码吗？", "声音小了些，后排听不清。", "收到后不合适，怎么申请退换？", "价格比我上次买的实惠。"};
            long[] counts = {4, 5, 7, 10, 16, 22, 18, 13, 9, 7, 5, 4};
            long seconds = Duration.between(query.start(), query.end()).getSeconds(), width = Math.max(1, (seconds + 11) / 12), total = 0;
            for (int i = 0; i < 12; i++) {
                if (width * i >= seconds) { buckets.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i, 0)); continue; }
                buckets.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i, counts[i]));
                total += counts[i];
                int[] categories = {0, 1, 2 + i % 6, 2 + (i + 3) % 6};
                for (int j = 0; j < categories.length; j++) {
                    String text = switch (categories[j]) {
                        case 0 -> texts[0];
                        case 1 -> texts[1];
                        case 2 -> texts[2];
                        case 3 -> texts[3];
                        case 4 -> texts[4];
                        case 5 -> texts[5];
                        case 6 -> texts[6];
                        default -> texts[7];
                    };
                    samples.add(new com.tianmu.agent.core.DanmakuTypes.Sample(Integer.toString(100000 + i * 4 + j), i, text, false));
                }
            }
            return new com.tianmu.agent.core.DanmakuTypes.Dataset(query, total, Math.min(total,35), buckets, samples, Instant.now(), "synthetic-danmaku-fixture", true);
        };
    }
}
