package com.tianmu.agent.tool.builtin;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Server-rendered HTML. Model supplies bounded observations with references, never executable markup. */
@Component
public final class CreateDanmakuReportTool implements AgentTool {
    private final ObjectMapper json;
    public CreateDanmakuReportTool(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public String name() { return "create_danmaku_report"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Evidence evidence = context.evidence(ToolArguments.text(call, "evidence_id"));
        Dataset data = evidence.analysis();
        if (data == null) throw new AgentException("INVALID_EVIDENCE", "Danmaku analysis evidence required");
        context.checkDanmakuQuery(data.query());
        List<Map<String, Object>> sections = sections(ToolArguments.text(call, "sections_json"), data);
        byte[] html = render(data, evidence.id(), sections).getBytes(StandardCharsets.UTF_8);
        var report = context.saveReport(evidence.id(), html);
        String text = (data.demo() ? "【模拟数据】" : "") + "videoId " + data.query().videoId() + " 的弹幕分析报告已生成。\n"
                + "范围：" + data.query().start() + " 至 " + data.query().end() + "（Asia/Shanghai，结束时刻不包含）。\n"
                + "已入库且仍保留的弹幕 " + data.totalMessages() + " 条，非空用户ID去重 " + data.uniqueSenders() + " 人。\n"
                + "内容观察基于 " + data.sampleOccurrenceCount() + " 条抽样片段，按文本去重为 " + data.samples().size() + " 种内容；不代表全量话题或情绪占比。\n"
                + "[查看 HTML 报告](" + report.url() + ")（需登录鉴权）";
        return new ToolResult(name(), Map.of("artifact_id", report.artifactId(), "url", report.url(), "status", "READY"), evidence.id(), new Answer(text, List.of(evidence.id())));
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sections(String source, Dataset data) {
        try {
            JsonNode root = json.readTree(source);
            if (!root.isArray() || root.size() > 6 || data.samples().isEmpty() && !root.isEmpty()) throw new IllegalArgumentException();
            List<Map<String, Object>> result = new ArrayList<>(); Set<String> valid = new HashSet<>();
            data.samples().forEach(s -> s.occurrences().forEach(o -> valid.add(o.id())));
            for (JsonNode node : root) {
                Set<String> names = new HashSet<>(); node.fieldNames().forEachRemaining(names::add);
                if (!node.isObject() || !names.equals(Set.of("title", "observation", "recommendation", "sample_ids"))) throw new IllegalArgumentException();
                for (String key : List.of("title", "observation", "recommendation")) if (!node.path(key).isTextual() || node.path(key).asText().isBlank()
                        || node.path(key).asText().length() > (key.equals("title") ? 80 : 600)) throw new IllegalArgumentException();
                JsonNode refs = node.path("sample_ids");
                if (!refs.isArray() || refs.isEmpty() || refs.size() > 4) throw new IllegalArgumentException();
                for (JsonNode ref : refs) if (!ref.isTextual() || !valid.contains(ref.textValue())) throw new IllegalArgumentException();
                result.add(json.convertValue(node, Map.class));
            }
            return List.copyOf(result);
        } catch (Exception e) { throw new AgentException("INVALID_REPORT", "Report sections must cite available samples and use the declared structure"); }
    }
    private static String render(Dataset d, String evidence, List<Map<String, Object>> sections) {
        StringBuilder out = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light\"><title>弹幕分析报告</title><style>");
        out.append(STYLES).append("</style></head><body><main class=\"report\">");
        out.append("<header class=\"hero\"><div class=\"eyebrow\">直播间弹幕分析</div><h1>")
                .append(d.demo() ? "<span class=\"demo-badge\">模拟数据</span>" : "")
                .append("videoId ").append(d.query().videoId()).append("</h1><p class=\"subtitle\">直播间互动概览与内容观察</p>")
                .append("<p class=\"range\"><span>统计时段</span>").append(esc(d.query().start().toString())).append(" 至 ")
                .append(esc(d.query().end().toString())).append(" <span class=\"timezone\">Asia/Shanghai · 左闭右开</span></p></header>")
                .append("<section class=\"section\" aria-labelledby=\"summary-title\"><div class=\"section-heading\"><div><div class=\"eyebrow\">SUMMARY</div><h2 id=\"summary-title\">数据概览</h2></div></div>")
                .append("<div class=\"metrics\"><article class=\"metric metric-primary\"><div class=\"metric-label\">已保留弹幕</div><div class=\"metric-value\">").append(d.totalMessages()).append("</div><div class=\"metric-unit\">条</div></article>")
                .append("<article class=\"metric\"><div class=\"metric-label\">发言用户</div><div class=\"metric-value\">").append(d.uniqueSenders()).append("</div><div class=\"metric-unit\">人 · 按非空 user_id 去重</div></article></div>")
                .append("<aside class=\"note\"><span class=\"note-mark\" aria-hidden=\"true\">i</span><p>仅统计当前仍保留的已入库记录，不代表全部发送或已删除弹幕。内容观察依据最多 48 条非随机片段，每个时间段取最早 4 条，不代表全量话题/情绪比例或因果结论。展示片段按文本完全一致去重；出现次数仅指抽取样本中的次数，不代表全量频次或独立用户数。片段可能被截短；手机号和邮箱作规则遮盖，但不能保证覆盖所有个人信息。</p></aside></section>")
                .append("<section class=\"section\" aria-labelledby=\"trend-title\"><div class=\"section-heading\"><div><div class=\"eyebrow\">ACTIVITY</div><h2 id=\"trend-title\">弹幕活跃趋势</h2></div><span class=\"section-hint\">按统计时段等分</span></div>")
                .append(trendChart(d))
                .append("<details class=\"trend-details\"><summary>查看各时段明细</summary><div class=\"table-wrap\"><table><caption class=\"sr-only\">每个时间段的弹幕数量</caption><thead><tr><th scope=\"col\">时间段</th><th scope=\"col\">弹幕量</th></tr></thead><tbody>");
        long seconds = Duration.between(d.query().start(), d.query().end()).getSeconds(), width = Math.max(1, (seconds + 11) / 12);
        for (Bucket b : d.buckets()) {
            if (width * b.index() >= seconds) continue;
            out.append("<tr><td class=\"time-cell\">").append(esc(d.query().start().plusSeconds(width * b.index()).toString())).append("<span class=\"time-separator\">—</span>")
                    .append(esc(d.query().start().plusSeconds(Math.min(seconds, width * (b.index() + 1))).toString())).append("</td><td class=\"count-cell\">").append(b.count())
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div></details></section>")
                .append("<section class=\"section\" aria-labelledby=\"insights-title\"><div class=\"section-heading\"><div><div class=\"eyebrow\">OBSERVATIONS</div><h2 id=\"insights-title\">内容观察与建议</h2></div></div>");
        if (sections.isEmpty()) out.append("<div class=\"empty-state\"><div class=\"empty-icon\" aria-hidden=\"true\">—</div><p>没有可用于内容分析的片段，或本次未形成可靠的内容观察。</p><p class=\"muted\">因此不推断观众情绪与话题。</p></div>");
        for (var section : sections) {
            out.append("<article class=\"insight-card\"><h3>").append(esc(section.get("title").toString())).append("</h3><p class=\"observation\">").append(esc(section.get("observation").toString()))
                    .append("</p><div class=\"recommendation\"><span class=\"recommendation-label\">运营建议</span><p>").append(esc(section.get("recommendation").toString()))
                    .append("</p></div><p class=\"references\">依据片段：");
            @SuppressWarnings("unchecked") List<String> refs = (List<String>) section.get("sample_ids");
            for (int i = 0; i < refs.size(); i++) {
                if (i > 0) out.append("、");
                out.append("<a href=\"#sample-").append(esc(refs.get(i))).append("\">#").append(esc(refs.get(i))).append("</a>");
            }
            out.append("</p></article>");
        }
        out.append("</section><section class=\"section\" aria-labelledby=\"evidence-title\"><div class=\"section-heading\"><div><div class=\"eyebrow\">SAMPLE EVIDENCE</div><h2 id=\"evidence-title\">抽样弹幕</h2></div><span class=\"section-hint\">").append(d.sampleOccurrenceCount()).append(" 条样本 · 去重后 ").append(d.samples().size()).append(" 种内容</span></div>");
        if (!d.samples().isEmpty()) out.append("<ol class=\"samples\">");
        for (Sample sample : d.samples()) {
            out.append("<li class=\"sample-card\">");
            for (SampleOccurrence member : sample.occurrences()) out.append("<span class=\"sample-anchor\" id=\"sample-").append(esc(member.id())).append("\"></span>");
            out.append("<div class=\"sample-meta\"><span class=\"sample-id\">#").append(esc(sample.id())).append("</span><span class=\"occurrences\">样本中出现 ").append(sample.occurrenceCount()).append(" 次</span>")
                    .append(sample.truncated() ? "<span class=\"truncated\">含截短片段</span>" : "").append("</div><p>").append(esc(sample.text())).append("</p>")
                    .append("<details class=\"sample-sources\"><summary>来源片段与时间段</summary><ul>");
            for (SampleOccurrence member : sample.occurrences()) out.append("<li>#").append(esc(member.id())).append(" · 时间段 ").append(member.bucket() + 1).append(member.truncated() ? " · 原文已截短" : "").append("</li>");
            out.append("</ul></details></li>");
        }
        if (!d.samples().isEmpty()) out.append("</ol>");
        out.append("</section><details class=\"methodology\"><summary>分析口径与数据追溯</summary><div class=\"methodology-content\"><dl><dt>证据 ID</dt><dd>").append(esc(evidence))
                .append("</dd><dt>数据来源</dt><dd>").append(esc(d.source())).append("</dd><dt>数据读取时点</dt><dd>")
                .append(esc(d.dataAsOf().toString())).append("</dd><dt>口径与版本</dt><dd>统计版本：danmaku-retained-v1；模板版本：1.3.0；抽样片段：").append(d.sampleOccurrenceCount()).append(" 条；去重内容：").append(d.samples().size()).append(" 种")
                .append("</dd></dl><p>模型观察为辅助判断，请结合内容和业务数据复核；仅凭弹幕无法计算在线人数、转化率或成交原因。</p></div></details>")
                .append("<footer class=\"footer\">弹幕分析报告 · 统计范围以页面所示时段为准</footer></main></body></html>");
        return out.toString();
    }
    private static String trendChart(Dataset d) {
        long seconds = Duration.between(d.query().start(), d.query().end()).getSeconds();
        long width = Math.max(1, (seconds + 11) / 12);
        List<Bucket> bins = d.buckets().stream().filter(b -> width * b.index() < seconds).toList();
        long peak = bins.stream().mapToLong(Bucket::count).max().orElse(0), scale = Math.max(1, peak);
        StringBuilder svg = new StringBuilder("<figure class=\"trend-chart\"><div class=\"chart-scroll\"><svg class=\"trend-svg\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 960 310\" role=\"img\" aria-labelledby=\"chart-title chart-desc\"><title id=\"chart-title\">弹幕活跃趋势折线图</title><desc id=\"chart-desc\">横轴为时间段，纵轴为弹幕条数。统计范围内弹幕 ");
        svg.append(d.totalMessages()).append(" 条，各点数量已标注，完整时间范围见下方明细。</desc><text x=\"66\" y=\"18\" class=\"axis-label\">弹幕量 / 条</text>");
        Set<Long> ticks = new TreeSet<>();
        for (int i = 0; i <= 4; i++) ticks.add(Math.round(scale * (i / 4.0)));
        for (long tick : ticks) {
            long y = 248 - Math.round(208.0 * tick / scale);
            svg.append("<line x1=\"66\" y1=\"").append(y).append("\" x2=\"928\" y2=\"").append(y).append("\" class=\"chart-grid\"/><text x=\"53\" y=\"").append(y + 4).append("\" text-anchor=\"end\" class=\"axis-label\">").append(tick).append("</text>");
        }
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < bins.size(); i++) {
            int x = bins.size() == 1 ? 497 : 82 + 830 * i / (bins.size() - 1);
            long y = 248 - Math.round(208.0 * bins.get(i).count() / scale);
            if (i > 0) points.append(' ');
            points.append(x).append(',').append(y);
        }
        svg.append("<polyline class=\"trend-line\" points=\"").append(points).append("\"/>");
        var timeFormat = java.time.format.DateTimeFormatter.ofPattern(seconds < 720 ? "HH:mm:ss" : "HH:mm");
        for (int i = 0; i < bins.size(); i++) {
            Bucket b = bins.get(i);
            int x = bins.size() == 1 ? 497 : 82 + 830 * i / (bins.size() - 1);
            long y = 248 - Math.round(208.0 * b.count() / scale);
            var start = d.query().start().plusSeconds(width * b.index());
            var end = d.query().start().plusSeconds(Math.min(seconds, width * (b.index() + 1)));
            svg.append("<circle class=\"trend-dot\" cx=\"").append(x).append("\" cy=\"").append(y).append("\" r=\"5\"><title>").append(esc(start.toString())).append(" 至 ").append(esc(end.toString())).append("：").append(b.count()).append(" 条</title></circle>")
                    .append("<text class=\"point-label\" x=\"").append(x).append("\" y=\"").append(y - 13).append("\" text-anchor=\"middle\">").append(b.count()).append("</text>");
            if (bins.size() <= 6 || i % 2 == 0 || i == bins.size() - 1) svg.append("<text class=\"axis-label\" x=\"").append(x).append("\" y=\"275\" text-anchor=\"middle\">").append(esc(start.format(timeFormat))).append("</text>");
        }
        return svg.append("<text class=\"axis-label\" x=\"928\" y=\"300\" text-anchor=\"end\">时间段起点 · Asia/Shanghai</text></svg></div><figcaption class=\"chart-caption\">每个点对应一个时间段的弹幕总量；横轴标注时段起点，悬停数据点可查看完整时段。</figcaption></figure>").toString();
    }
    private static final String STYLES = """
            :root{color-scheme:light;--ink:#172b3a;--muted:#607282;--line:#e3eaf0;--blue:#176b87;--blue-soft:#eaf5f8;--surface:#fff;--canvas:#f4f7fa;--amber:#875613}
            *{box-sizing:border-box}html{background:var(--canvas)}body{margin:0;padding:40px 22px;color:var(--ink);font:15px/1.7 system-ui,-apple-system,"Segoe UI",sans-serif}
            .report{max-width:1040px;margin:0 auto}.hero{padding:34px 38px 30px;background:linear-gradient(125deg,#123e56,#176b87);border-radius:18px;color:#fff;box-shadow:0 10px 28px #153c5920}
            .eyebrow{font-size:11px;letter-spacing:.14em;font-weight:750;color:#6d8493}.hero .eyebrow{color:#b9dbe7}.hero h1{margin:10px 0 2px;font-size:clamp(27px,4vw,38px);line-height:1.2;letter-spacing:-.03em}.subtitle{margin:8px 0 22px;color:#d7e9ef}
            .range{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:0;padding-top:17px;border-top:1px solid #ffffff35;font-size:14px}.range>span:first-child{font-weight:700}.timezone{color:#c6e0e8}.demo-badge{display:inline-block;vertical-align:5px;margin-right:10px;padding:4px 8px;border:1px solid #f2d18b;border-radius:999px;background:#fff3d5;color:#805313;font-size:12px;letter-spacing:0}
            .section{margin-top:34px}.section-heading{display:flex;align-items:end;justify-content:space-between;gap:16px;margin-bottom:15px}.section-heading .eyebrow{margin-bottom:3px}.section h2{margin:0;font-size:22px;letter-spacing:-.02em}.section-hint{padding-bottom:3px;color:var(--muted);font-size:13px}
            .metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.metric{position:relative;min-height:132px;padding:21px 24px;background:var(--surface);border:1px solid var(--line);border-radius:14px;box-shadow:0 3px 12px #173b5010}.metric-primary{border-top:3px solid var(--blue)}.metric-label{color:var(--muted);font-size:13px;font-weight:650}.metric-value{display:inline-block;margin-top:5px;font-size:36px;font-weight:760;line-height:1.2;letter-spacing:-.04em}.metric-unit{display:inline-block;margin-left:9px;color:var(--muted);font-size:13px}
            .note{display:flex;gap:12px;margin-top:14px;padding:14px 17px;border:1px solid #ecd9b5;border-radius:12px;background:#fff9ed;color:#684c24;font-size:13px}.note p{margin:0}.note-mark{display:grid;flex:0 0 20px;place-items:center;width:20px;height:20px;border-radius:50%;background:#f2e3c7;color:var(--amber);font-size:12px;font-weight:800}
            .table-wrap{overflow-x:auto;background:var(--surface);border:1px solid var(--line);border-radius:14px}.table-wrap table{width:100%;border-collapse:collapse;text-align:left;font-variant-numeric:tabular-nums}.table-wrap th,.table-wrap td{padding:12px 16px;border-bottom:1px solid var(--line)}.table-wrap th{background:#f8fafb;color:var(--muted);font-size:12px;font-weight:700}.table-wrap tbody tr:last-child td{border-bottom:0}.time-cell{min-width:280px;font-size:13px}.time-separator{padding:0 7px;color:#97a7b2}.count-cell{width:100px;font-weight:700}.bar-track{height:9px;min-width:100px;overflow:hidden;border-radius:99px;background:#edf2f5}.bar{height:100%;min-width:0;border-radius:inherit;background:linear-gradient(90deg,#3b9db1,#176b87)}.chart-caption{margin:8px 2px 0;color:var(--muted);font-size:12px}
            .empty-state{padding:26px;background:var(--surface);border:1px dashed #cbd7df;border-radius:14px;text-align:center}.empty-state p{margin:3px}.empty-icon{color:#8ba0ad;font-size:28px}.muted{color:var(--muted);font-size:13px}.insight-card{margin-top:12px;padding:21px 23px;background:var(--surface);border:1px solid var(--line);border-radius:14px;box-shadow:0 3px 12px #173b500a}.insight-card h3{margin:0 0 9px;font-size:17px}.observation{margin:0 0 15px}.recommendation{display:flex;gap:11px;padding:12px 14px;border-radius:10px;background:var(--blue-soft)}.recommendation-label{flex:0 0 auto;color:var(--blue);font-size:12px;font-weight:750}.recommendation p{margin:0;font-size:14px}.references{margin:13px 0 0;color:var(--muted);font-size:12px}.references a{color:var(--blue);font-weight:650;text-decoration:none}.references a:hover{text-decoration:underline}
            .trend-chart{margin:0;padding:18px 20px 14px;background:var(--surface);border:1px solid var(--line);border-radius:14px}.chart-scroll{overflow-x:auto}.trend-svg{display:block;width:100%;min-width:640px;height:auto}.chart-grid{stroke:#e7edf2;stroke-width:1;stroke-dasharray:4 5}.axis-label{font-size:12px;fill:var(--muted)}.point-label{font-size:13px;font-weight:700;fill:var(--blue)}.trend-line{fill:none;stroke:var(--blue);stroke-width:3;stroke-linecap:round;stroke-linejoin:round}.trend-dot{fill:#fff;stroke:var(--blue);stroke-width:3}.trend-details{margin-top:12px;font-size:13px}.trend-details>summary{cursor:pointer;color:var(--blue);font-weight:650;padding:7px 2px}.trend-details .table-wrap{margin-top:8px}.occurrences{margin-left:auto;padding:2px 8px;border-radius:99px;background:var(--blue-soft);color:var(--blue);font-weight:650}.sample-anchor{display:block;scroll-margin-top:25px}.sample-card:has(.sample-anchor:target){border-color:#56a0b3;box-shadow:0 0 0 3px #176b8718}.sample-sources{margin-top:12px;color:var(--muted);font-size:12px}.sample-sources summary{cursor:pointer}.sample-sources ul{margin:7px 0 0;padding-left:18px;max-height:150px;overflow:auto}
            .samples{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0;padding:0;list-style:none}.sample-card{min-width:0;padding:14px 16px;background:var(--surface);border:1px solid var(--line);border-radius:12px;scroll-margin-top:16px}.sample-card:target{border-color:#56a0b3;box-shadow:0 0 0 3px #176b8718}.sample-meta{display:flex;align-items:center;gap:8px;flex-wrap:wrap;color:var(--muted);font-size:11px}.sample-id{color:var(--blue);font-weight:750}.sample-bucket{padding-left:8px;border-left:1px solid var(--line)}.truncated{padding:1px 6px;border-radius:99px;background:#fff3d5;color:#805313}.sample-card p{margin:9px 0 0;white-space:pre-wrap;overflow-wrap:anywhere}.methodology{margin-top:30px;background:var(--surface);border:1px solid var(--line);border-radius:13px}.methodology summary{padding:15px 18px;cursor:pointer;font-weight:700}.methodology-content{padding:0 18px 17px;border-top:1px solid var(--line);color:var(--muted);font-size:13px}.methodology-content dl{display:grid;grid-template-columns:130px minmax(0,1fr);gap:7px 14px;margin:14px 0}.methodology-content dt{font-weight:700;color:var(--ink)}.methodology-content dd{margin:0;overflow-wrap:anywhere}.methodology-content p{margin:10px 0 0}.footer{padding:20px 0 4px;color:#81919c;font-size:12px;text-align:center}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
            @media(max-width:640px){body{padding:18px 12px}.hero{padding:25px 22px 22px;border-radius:14px}.range{align-items:flex-start}.section{margin-top:27px}.section h2{font-size:20px}.metrics{grid-template-columns:1fr}.metric{min-height:112px;padding:17px 20px}.table-wrap th,.table-wrap td{padding:10px 12px}.time-cell{min-width:245px}.samples{grid-template-columns:1fr}.insight-card{padding:18px}.methodology-content dl{grid-template-columns:1fr;gap:2px}.methodology-content dd{margin-bottom:8px}}
            @media print{html,body{background:#fff}.report{max-width:none}.hero{box-shadow:none;print-color-adjust:exact;-webkit-print-color-adjust:exact}.section,.metric,.insight-card,.sample-card,.methodology{break-inside:avoid}.methodology-content{display:block}}
            """;
    private static String esc(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
