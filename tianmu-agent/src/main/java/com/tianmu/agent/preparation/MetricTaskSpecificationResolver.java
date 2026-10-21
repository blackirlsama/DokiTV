package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class MetricTaskSpecificationResolver implements TaskSpecificationResolver {
    public Optional<Specification> resolve(String original, LocalDate businessDate) {
        var ids = Pattern.compile("视频\\s*(\\d+)").matcher(original).results().map(m -> m.group(1)).toList();
        var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(original).results().map(m -> m.group()).toList();
        if (original.contains("弹幕") && ids.size() == 1 && (dates.size() == 1 || dates.isEmpty() && original.contains("昨天"))) {
            try {
                long video = Long.parseLong(ids.get(0));
                String date = dates.isEmpty() ? businessDate.minusDays(1).toString() : LocalDate.parse(dates.get(0)).toString();
                return Optional.of(new Specification("video.metric", Map.of("video_id", video, "date", date, "metric", "bullet_count"),
                        Map.of("video_id", "user_input", "date", dates.isEmpty() ? "relative_date@" + businessDate : "user_input", "metric", "user_input")));
            } catch (IllegalArgumentException | DateTimeException ignored) {}
        }
        return Optional.empty();
    }
}
