package com.orangecode.tianmu.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orangecode.tianmu.utils.JwtUtil;
import com.zaxxer.hikari.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Test-only seed/token issuer, never included in production JARs. Writes tokens only to a private output file. */
public final class BusinessFixtureMain {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("BUSINESS_JDBC_URL");
        if(args.length!=1 || url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+_it(?:\\?.*)?"))
            throw new IllegalArgumentException("Use a private output file and dedicated local _it database");
        HikariConfig config=new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("BUSINESS_DB_USER"));
        config.setPassword(System.getenv("BUSINESS_DB_PASSWORD")); config.setMaximumPoolSize(2); config.setConnectionInitSql("SET time_zone='+00:00'");
        try(HikariDataSource source=new HikariDataSource(config)) {
            new ResourceDatabasePopulator(new ClassPathResource("business-schema.sql")).execute(source);
            JdbcTemplate db=new JdbcTemplate(source);
            db.update("DELETE FROM bullet WHERE video_id IN(1001,1002,1003)"); db.update("DELETE FROM video_stats WHERE video_id IN(1001,1002,1003)");
            for(long id:List.of(881L,882L)) db.update("INSERT INTO user(user_id,state,is_delete) VALUES(?,'normal',0) ON DUPLICATE KEY UPDATE state='normal',is_delete=0",id);
            for(long id:List.of(1001L,1002L,1003L)) db.update("INSERT INTO video(video_id,user_id,is_delete) VALUES(?,?,0) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),is_delete=0",id,id==1002?882:881);
            LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
            Instant start=day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            long[] offsets={-1,0,1000,86399999,86400000};
            for(int i=0;i<offsets.length;i++) db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,color,playback_time,create_time) VALUES(?,1001,881,'fixture','ffffff',1,?)",90001+i,Timestamp.from(start.plusMillis(offsets[i])));
            db.update("INSERT INTO video_stats(video_id,bullet_count) VALUES(1001,999)");
            LettuceConnectionFactory factory=new LettuceConnectionFactory(System.getenv("BUSINESS_REDIS_HOST"),Integer.parseInt(System.getenv("BUSINESS_REDIS_PORT")));
            factory.afterPropertiesSet();
            try {
                StringRedisTemplate redis=new StringRedisTemplate(factory); redis.afterPropertiesSet();
                String alice=JwtUtil.generate("881"),bob=JwtUtil.generate("882");
                redis.opsForValue().set("881",alice,Duration.ofMinutes(15)); redis.opsForValue().set("882",bob,Duration.ofMinutes(15));
                Map<String,Object> report=new LinkedHashMap<>();
                report.put("tokens",Map.of("alice",alice,"bob",bob)); report.put("queryDate",day.toString()); report.put("expectedValue",3);
                report.put("referenceCount",db.queryForObject("SELECT COUNT(*) FROM bullet WHERE video_id=1001 AND create_time>=? AND create_time<?",Long.class,Timestamp.from(start),Timestamp.from(start.plusSeconds(86400))));
                report.put("storedCounter",999);
                Files.writeString(Path.of(args[0]),new ObjectMapper().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
                System.out.println("Business fixtures ready: retained daily count=3; cumulative drift fixture=999; tokens kept private.");
            } finally { factory.destroy(); }
        }
    }
}
