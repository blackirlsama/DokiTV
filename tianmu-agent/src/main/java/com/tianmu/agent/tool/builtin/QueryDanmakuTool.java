package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.query.DanmakuQueryPort;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class QueryDanmakuTool implements AgentTool {
    private final DanmakuQueryPort queries;
    public QueryDanmakuTool(DanmakuQueryPort queries) { this.queries = queries; }
    public String name() { return "query_danmaku"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Query query = new Query(ToolArguments.integer(call, "video_id"), LocalDateTime.parse(ToolArguments.text(call, "start")), LocalDateTime.parse(ToolArguments.text(call, "end")));
        context.checkDanmakuQuery(query);
        Dataset raw = context.query(control -> queries.query(query, context.scope(), control));
        if (raw == null || !raw.query().equals(query) || raw.dataAsOf().isAfter(Instant.now().plusSeconds(5)))
            throw new AgentException("INVALID_EVIDENCE", "Analysis response does not match the query");
        Map<String, List<Sample>> grouped = new LinkedHashMap<>();
        for (Sample sample : raw.samples()) {
            String safeText = redact(sample.text());
            grouped.computeIfAbsent(safeText, ignored -> new ArrayList<>()).add(sample);
        }
        List<Sample> samples = grouped.entrySet().stream().map(entry -> {
            List<Sample> matches = entry.getValue();
            Sample first = matches.get(0);
            List<SampleOccurrence> occurrences = matches.stream()
                    .map(s -> new SampleOccurrence(s.id(), s.bucket(), s.truncated())).toList();
            return new Sample(first.id(), first.bucket(), entry.getKey(), matches.stream().anyMatch(Sample::truncated), occurrences);
        }).toList();
        Dataset data = new Dataset(query, raw.totalMessages(), raw.uniqueSenders(), raw.buckets(), samples, raw.dataAsOf(), raw.source(), raw.demo());
        Evidence evidence = context.registerDanmakuEvidence(data);
        return new ToolResult(name(), Map.of("analysis", data, "sampling_method", data.samplingMethod(), "evidence_id", evidence.id()), evidence.id());
    }
    private static String redact(String text) { return text.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[email]").replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[phone]"); }
}
