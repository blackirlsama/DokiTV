package com.orangecode.tianmu.agentbridge;

import com.zaxxer.hikari.*;
import javax.sql.DataSource;
import java.util.Objects;

/** A private UTC/read-only pool. It never changes sessions in the application's write pool. */
public final class BusinessReadDatabase implements AutoCloseable {
    private final HikariDataSource source;
    public BusinessReadDatabase(DataSource existing, String url, String user, String password) throws Exception {
        HikariDataSource original = existing.unwrap(HikariDataSource.class);
        boolean dedicated = url != null && !url.isBlank();
        String selected = dedicated ? url : original.getJdbcUrl();
        if (selected == null || !selected.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL read database required");
        selected = selected.replaceAll("(?i)(serverTimezone|connectionTimeZone)=[^&]*", "$1=UTC");
        selected += (selected.contains("?") ? "&" : "?") + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(selected);
        config.setUsername(dedicated ? Objects.requireNonNull(user) : original.getUsername());
        config.setPassword(dedicated ? Objects.requireNonNull(password) : original.getPassword());
        config.setPoolName("agent-business-reader"); config.setMaximumPoolSize(4); config.setMinimumIdle(1);
        config.setReadOnly(true); config.setConnectionTimeout(3000); config.setConnectionInitSql("SET time_zone='+00:00'");
        config.addDataSourceProperty("connectTimeout","3000"); config.addDataSourceProperty("socketTimeout","3000");
        source = new HikariDataSource(config);
    }
    public DataSource source() { return source; }
    @Override public void close() { source.close(); }
}
