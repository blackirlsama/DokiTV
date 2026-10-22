package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletIngestionService;
import com.orangecode.tianmu.utils.JwtUtil;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BUSINESS_IT", matches="true")
class BusinessIntegrationTest {
    HikariDataSource source; JdbcTemplate db; LettuceConnectionFactory factory; StringRedisTemplate redis;
    BusinessAuthentication auth; BusinessMetrics metrics; TransactionTemplate transaction;
    final LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
    String alice, bob;
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("BUSINESS_JDBC_URL");
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+_it(?:\\?.*)?"), "Dedicated local _it database required");
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("BUSINESS_DB_USER"));
        config.setPassword(System.getenv("BUSINESS_DB_PASSWORD")); config.setMaximumPoolSize(4); config.setConnectionInitSql("SET time_zone='+00:00'");
        source = new HikariDataSource(config); db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("business-schema.sql")).execute(source);
        db.update("DELETE FROM bullet WHERE video_id IN (1001,1002,1003)");
        db.update("DELETE FROM video_stats WHERE video_id IN (1001,1002,1003)");
        for (long id : List.of(881L,882L)) db.update("INSERT INTO user(user_id,state,is_delete) VALUES(?,'normal',0) ON DUPLICATE KEY UPDATE state='normal',is_delete=0", id);
        for (long video : List.of(1001L,1002L,1003L)) db.update("INSERT INTO video(video_id,user_id,is_delete) VALUES(?,?,0) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),is_delete=0", video, video == 1002 ? 882 : 881);
        factory = new LettuceConnectionFactory(System.getenv("BUSINESS_REDIS_HOST"), Integer.parseInt(System.getenv("BUSINESS_REDIS_PORT")));
        factory.afterPropertiesSet(); redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        alice = JwtUtil.generate("881"); bob = JwtUtil.generate("882");
        redis.opsForValue().set("881", alice, Duration.ofMinutes(5)); redis.opsForValue().set("882", bob, Duration.ofMinutes(5));
        var properties = new BridgeProperties("tianmu", Set.of("normal"), "Asia/Shanghai", 2);
        auth = new BusinessAuthentication(db, redis, properties); metrics = new BusinessMetrics(db, properties);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterEach void close() { if (factory != null) { redis.delete(List.of("881","882")); factory.destroy(); } if (source != null) source.close(); }
    BusinessAuthentication.Principal principal() { return auth.authenticate("Bearer " + alice); }
    Instant start() { return day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    void bullet(long id, Instant time) {
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,color,playback_time,create_time) VALUES(?,1001,881,'fixture','ffffff',1,?)", id, Timestamp.from(time));
    }
    @Test void realJwtAndRedisSessionAreBothRequired() {
        assertEquals("881", principal().userId());
        assertEquals("UNAUTHORIZED", assertThrows(BridgeException.class, () -> auth.authenticate("invalid")).code());
        redis.delete("881"); assertEquals(401, assertThrows(BridgeException.class, this::principal).status());
    }
    @Test void disabledAndDeletedUsersCannotAuthenticate() {
        db.update("UPDATE user SET state='disabled' WHERE user_id=881"); assertThrows(BridgeException.class, this::principal);
        db.update("UPDATE user SET state='normal',is_delete=1 WHERE user_id=881"); assertThrows(BridgeException.class, this::principal);
    }
    @Test void authorizeOnlyRequestedOwnedVideosAndCheckAgainAtQuery() {
        assertEquals(Set.of(1001L), metrics.allowed(principal(), Set.of(1001L,1002L)));
        assertEquals(403, assertThrows(BridgeException.class, () -> metrics.query(principal(), 1002, day, "bullet_count")).status());
        db.update("UPDATE video SET user_id=882 WHERE video_id=1001");
        assertThrows(BridgeException.class, () -> metrics.query(principal(),1001,day,"bullet_count"));
    }
    @Test void halfOpenBusinessDayCountsRowsInsteadOfCumulativeCounter() {
        bullet(1,start().minusMillis(1)); bullet(2,start()); bullet(3,start().plusSeconds(123));
        bullet(4,start().plusSeconds(86400).minusMillis(1)); bullet(5,start().plusSeconds(86400));
        db.update("INSERT INTO video_stats(video_id,bullet_count) VALUES(1001,999)");
        var result = metrics.query(principal(),1001,day,"bullet_count");
        assertEquals(3,result.value()); assertFalse(result.demo()); assertTrue(result.complete());
        assertEquals("mysql:bullet:retained-persisted-v1",result.source());
        assertEquals(0,metrics.query(principal(),1003,day,"bullet_count").value());
    }
    @Test void deletingRowsChangesRetainedMetricAndDatesAreBounded() {
        bullet(1,start()); bullet(2,start().plusSeconds(1)); db.update("DELETE FROM bullet WHERE bullet_id=1");
        assertEquals(1,metrics.query(principal(),1001,day,"bullet_count").value());
        assertThrows(BridgeException.class, () -> metrics.query(principal(),1001,day.plusDays(1),"bullet_count"));
        assertThrows(BridgeException.class, () -> metrics.query(principal(),1001,day.minusDays(31),"bullet_count"));
    }
    @Test void datetimeColumnUsesConfiguredStorageWallClockZone() {
        db.execute("ALTER TABLE bullet MODIFY create_time DATETIME(3) NOT NULL");
        try {
            var service = new BulletIngestionService(db);
            transaction.executeWithoutResult(s -> service.persist(event(9001)));
            db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(2,1001,881,'fixture',1,?)",day.plusDays(1)+" 00:00:00");
            assertEquals(1,metrics.query(principal(),1001,day,"bullet_count").value());
        } finally {
            db.update("DELETE FROM bullet WHERE video_id=1001");
            db.execute("ALTER TABLE bullet MODIFY create_time TIMESTAMP(3) NOT NULL");
        }
    }
    @Test void privateReadPoolRejectsWritesAndDoesNotChangeWriterTimeZone() throws Exception {
        try (var writer = source.getConnection()) {
            writer.createStatement().execute("SET time_zone='+08:00'");
            try (var reader = new BusinessReadDatabase(source,"","","")) {
                JdbcTemplate read = new JdbcTemplate(reader.source());
                assertEquals("+00:00",read.queryForObject("SELECT @@session.time_zone",String.class));
                assertThrows(Exception.class,()->read.update("UPDATE video SET user_id=882 WHERE video_id=1001"));
                try (var result = writer.createStatement().executeQuery("SELECT @@session.time_zone")) {
                    assertTrue(result.next()); assertEquals("+08:00",result.getString(1));
                }
            } finally { writer.createStatement().execute("SET time_zone='+00:00'"); }
        }
    }
    SendBulletRequest event(long id) {
        var event = new SendBulletRequest(); event.setBulletId(id); event.setVideoId(1001L); event.setUserId(881L);
        event.setContent("idempotent"); event.setColor("ffffff"); event.setPlaybackTime(1d); event.setCreateTime(Date.from(start())); return event;
    }
    @Test void concurrentDuplicateEventCreatesOneRowAndOneCounterIncrement() throws Exception {
        var service = new BulletIngestionService(db); var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i=0;i<8;i++) calls.add(pool.submit(() -> transaction.executeWithoutResult(s -> service.persist(event(9001)))));
            for (var call : calls) call.get(10,TimeUnit.SECONDS);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM bullet WHERE bullet_id=9001",Long.class));
            assertEquals(1,db.queryForObject("SELECT bullet_count FROM video_stats WHERE video_id=1001",Long.class));
            assertEquals(1,metrics.query(principal(),1001,day,"bullet_count").value());
        } finally { pool.shutdownNow(); }
    }
    @Test void eventIdPayloadConflictCannotIncrementCounter() {
        var service = new BulletIngestionService(db); transaction.executeWithoutResult(s -> service.persist(event(9001)));
        var changed = event(9001); changed.setContent("tampered");
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(s -> service.persist(changed)));
        assertEquals(1,db.queryForObject("SELECT bullet_count FROM video_stats WHERE video_id=1001",Long.class));
    }
    @Test void counterFailureRollsBackBulletInsertion() {
        String trigger="business_it_counter_fault";
        db.execute("DROP TRIGGER IF EXISTS " + trigger);
        db.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON video_stats FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test counter fault'");
        try {
            var service = new BulletIngestionService(db);
            assertThrows(Exception.class, () -> transaction.executeWithoutResult(s -> service.persist(event(9001))));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM bullet WHERE bullet_id=9001",Long.class));
        } finally { db.execute("DROP TRIGGER " + trigger); }
    }
}
