package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.BusinessQueryPort;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class QueryVideoMetricsTool implements AgentTool {
    private final BusinessQueryPort queries;
    public QueryVideoMetricsTool(BusinessQueryPort queries) { this.queries = queries; }
    public String name() { return "query_video_metrics"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        long videoId = ToolArguments.integer(call, "video_id");
        MetricQuery query = new MetricQuery(videoId, LocalDate.parse(ToolArguments.text(call, "date")), ToolArguments.text(call, "metric"));
        context.checkQuery(query);
        MetricData data = context.query(control -> queries.query(query, context.scope(), control));
        context.checkpoint();
        if (data == null || data.videoId() != videoId || !data.date().equals(query.date()) || !data.metric().equals(query.metric()))
            throw new AgentException("INVALID_EVIDENCE", "Query adapter returned inconsistent evidence");
        Evidence evidence = context.registerEvidence(data);
        return new ToolResult(name(), Map.of("video_id", videoId, "date", query.date().toString(),
                "metric", data.metric(), "value", data.value(), "metric_version", data.metricVersion(),
                "source", data.source(), "data_as_of", data.dataAsOf().toString(),
                "complete", data.complete(), "demo", data.demo(), "evidence_id", evidence.id()), evidence.id());
    }
}
