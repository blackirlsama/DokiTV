package com.dokitv.agent.preparation;

import com.dokitv.agent.core.PreparationTypes.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Component
@org.springframework.core.annotation.Order(0)
public class MetricQueryReadiness implements QueryReadinessPolicy {
    public Optional<Clarification> validate(Input input) {
        if (!input.query().contains("弹幕")) return Optional.empty();
        if (!Pattern.compile("视频\\s*\\d+").matcher(input.query()).find()) return ask("请提供要查询的视频ID。");
        var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(input.query());
        LocalDate date;
        try {
            if (dates.find()) date = LocalDate.parse(dates.group());
            else if (input.query().contains("昨天")) date = input.businessDate().minusDays(1);
            else return ask("请指定一个 YYYY-MM-DD 格式的日期，或使用“昨天”。");
        } catch (DateTimeException e) { return ask("日期无效，请提供 YYYY-MM-DD 格式的日期。"); }
        if (!date.isBefore(input.businessDate()) || date.isBefore(input.businessDate().minusDays(30)))
            return ask("请选择最近30天内已结束的自然日期。");
        return Optional.empty();
    }
    private Optional<Clarification> ask(String question) { return Optional.of(new Clarification(true, question)); }
}
