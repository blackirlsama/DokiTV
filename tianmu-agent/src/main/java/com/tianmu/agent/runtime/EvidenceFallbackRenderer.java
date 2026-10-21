package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import java.util.*;

/** Deliberately narrow fallback: only complete metric evidence; never arbitrary tool JSON. */
public final class EvidenceFallbackRenderer {
    private EvidenceFallbackRenderer() {}
    public static Answer render(RunState run) {
        List<Evidence> evidence = run.execution().evidence().snapshot().values().stream().filter(item -> item.runId().equals(run.id)
                && item.owner().equals(run.owner) && run.owner.videoIds().contains(item.data().videoId())
                && item.data().complete() && item.data().value() >= 0 && item.data().metric().equals("bullet_count")
                && item.data().timezone().equals("Asia/Shanghai"))
                .sorted(Comparator.comparing((Evidence item) -> item.data().date()).thenComparing(item -> item.data().videoId()))
                .limit(10).toList();
        if (evidence.isEmpty()) throw new AgentException("STEP_LIMIT", "No usable evidence for finalization");
        StringJoiner text = new StringJoiner("\n");
        if (evidence.stream().anyMatch(item -> item.data().demo())) text.add("【模拟数据】");
        for (Evidence item : evidence) text.add("视频 " + item.data().videoId() + " 在 " + item.data().date()
                + " 的弹幕数为 " + item.data().value() + " 条。");
        text.add("以上仅陈列已查询到的指标，尚不足以证明变化原因。");
        return new Answer(text.toString(), evidence.stream().map(Evidence::id).toList());
    }
}
