package com.tianmu.agent.adapters.business;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.BusinessQueryPort;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/** Reads explicitly seeded demo metrics from the Agent-only MySQL database. */
@Configuration(proxyBeanMethods = false)
@Profile("demo & persistent & !business")
public class DemoMysqlBusinessQueryAdapter {
    @Bean
    @ConditionalOnMissingBean(BusinessQueryPort.class)
    BusinessQueryPort mysqlDemoQuery(DataSource dataSource) {
        return new MysqlDemoQuery(new JdbcTemplate(dataSource));
    }

    static final class MysqlDemoQuery implements BusinessQueryPort {
        private final JdbcTemplate db;

        MysqlDemoQuery(JdbcTemplate db) {
            this.db = db;
            this.db.setQueryTimeout(5);
        }

        @Override
        public MetricData query(MetricQuery query, AccessScope scope) {
            return query(query, scope, null);
        }

        @Override
        public MetricData query(MetricQuery query, AccessScope scope, CallControl control) {
            if (control != null) control.checkpoint();
            if (!"demo".equals(scope.tenantId()) || !scope.videoIds().contains(query.videoId()))
                throw new AgentException("FORBIDDEN", "Video is outside demo data scope");
            if (!"bullet_count".equals(query.metric()))
                throw new AgentException("METRIC_UNSUPPORTED", "Unsupported demo metric");

            var rows = db.query("""
                    SELECT video_id, metric_date, metric_name, metric_value, metric_version,
                           source, data_as_of, timezone_name, is_complete, is_demo
                    FROM demo_video_metric
                    WHERE tenant_id = ? AND video_id = ? AND metric_date = ? AND metric_name = ?
                    """, MysqlDemoQuery::mapMetric,
                    scope.tenantId(), query.videoId(), query.date(), query.metric());
            if (control != null) control.checkpoint();
            if (rows.isEmpty())
                throw new AgentException("METRIC_NOT_FOUND", "No seeded demo metric exists for this video and date");
            return rows.get(0);
        }

        private static MetricData mapMetric(ResultSet rs, int row) throws SQLException {
            Timestamp dataAsOf = rs.getTimestamp("data_as_of");
            return new MetricData(rs.getLong("video_id"), rs.getDate("metric_date").toLocalDate(),
                    rs.getString("metric_name"), rs.getLong("metric_value"), rs.getString("metric_version"),
                    rs.getString("source"), dataAsOf.toInstant(), rs.getString("timezone_name"),
                    rs.getBoolean("is_complete"), rs.getBoolean("is_demo"));
        }
    }
}
