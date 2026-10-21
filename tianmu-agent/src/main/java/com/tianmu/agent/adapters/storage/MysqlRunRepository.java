package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.MemoryTypes;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.events.RunEventStore;
import com.tianmu.agent.runtime.store.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.*;
import org.springframework.scheduling.annotation.Scheduled;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One authoritative writer per Agent database in v0.7. All terminal rows share one transaction. */
public final class MysqlRunRepository implements RunRepository, AutoCloseable {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json;
    private final RedisMemoryCache cache; private final ObjectStore objects; private final Duration timeout; private final int maxCalls;
    private final MemoryBuilder memoryBuilder = new MemoryBuilder();
    private final String instance = UUID.randomUUID().toString();
    private final Connection writer; private final String writerLock;
    private final Map<UUID, Map<UUID, Runnable>> listeners = new ConcurrentHashMap<>();
    private record Meta(UUID id, UUID session, long turn, long generation, long historyVersion, AccessScope owner,
                        String message, String query, Instant created, Instant deadline) {}

    public MysqlRunRepository(DataSource source, ObjectMapper json, RedisMemoryCache cache, ObjectStore objects, Duration timeout, int maxCalls) {
        db = new JdbcTemplate(source); db.setQueryTimeout(5);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setTimeout(5);
        this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); this.cache = cache; this.objects = objects; this.timeout = timeout; this.maxCalls = maxCalls;
        try {
            writer = source.getConnection(); writerLock = "tianmu.writer." + hash(writer.getCatalog()).substring(0, 40);
            try (PreparedStatement statement = writer.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, writerLock);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) { writer.close(); throw new IllegalStateException("An Agent storage writer is already running; v0.7 supports one writer per database"); }
                }
            }
        } catch (SQLException e) { throw unavailable(); }
        // Owning the database writer lock proves the former writer has lost its connection.
        recoverInterrupted();
    }
    @Override public boolean durable() { return true; }
    private AgentException unavailable() { return new AgentException("STORAGE_UNAVAILABLE", "Persistent storage is unavailable; retry using the same Idempotency-Key"); }
    private synchronized void guard() {
        try (PreparedStatement statement = writer.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            statement.setString(1, writerLock);
            try (ResultSet result = statement.executeQuery()) { if (!result.next() || result.getInt(1) != 1) throw unavailable(); }
        } catch (SQLException e) { throw unavailable(); }
    }
    private <T> T read(Supplier<T> action) { try { return action.get(); } catch (DataAccessException e) { throw unavailable(); } }
    private <T> T write(Supplier<T> action) { guard(); return read(() -> tx.execute(status -> action.get())); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new AgentException("STORAGE_ENCODING_ERROR", "Unsupported persistent payload"); }
    }
    private <T> T decode(Object body, Class<T> type) {
        try { return json.readValue(body.toString(), type); } catch (Exception e) { throw new AgentException("STORAGE_CORRUPT", "Stored record is inconsistent"); }
    }
    private String hash(String text) { return FileObjectStore.hash(text.getBytes(StandardCharsets.UTF_8)); }
    private Timestamp stamp(Instant time) { return Timestamp.from(time); }
    private Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Meta meta(Map<String, Object> row) {
        Map<?, ?> request = decode(row.get("request_json"), Map.class);
        return new Meta(UUID.fromString(row.get("run_id").toString()), UUID.fromString(row.get("session_id").toString()),
                number(row, "turn_seq"), number(row, "session_generation"), number(row, "base_history_version"),
                decode(row.get("access_scope_json"), AccessScope.class), request.get("message").toString(),
                row.get("execution_query").toString(), instant(row, "created_at"), instant(row, "deadline_at"));
    }
    private Map<String, Object> session(UUID id, AccessScope owner, boolean lock) {
        var rows = db.queryForList("SELECT * FROM agent_session WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND status='OPEN' AND expires_at>UTC_TIMESTAMP(6)" + (lock ? " FOR UPDATE" : ""), owner.tenantId(), owner.userId(), id.toString());
        if (rows.isEmpty()) throw new AgentException("SESSION_NOT_FOUND", "Session is missing or expired"); return rows.get(0);
    }
    private Map<String, Object> row(UUID id, AccessScope owner, boolean lock) {
        return row(id, owner, lock, true);
    }
    private Map<String, Object> row(UUID id, AccessScope owner, boolean lock, boolean checkVideos) {
        var rows = db.queryForList("SELECT * FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND data_expires_at>UTC_TIMESTAMP(6)" + (lock ? " FOR UPDATE" : ""), owner.tenantId(), owner.userId(), id.toString());
        if (rows.isEmpty()) throw new AgentException("RUN_NOT_FOUND", "Run is missing or expired");
        Map<String, Object> value = rows.get(0); AccessScope saved = decode(value.get("access_scope_json"), AccessScope.class);
        if (checkVideos && !owner.videoIds().containsAll(saved.videoIds())) throw new AgentException("FORBIDDEN", "Historical scope is no longer accessible");
        return value;
    }
    private Snapshot snapshot(Map<String, Object> row) { return decode(row.get("snapshot_json"), Snapshot.class); }
    private boolean terminal(Map<String, Object> row) { return AgentTypes.terminal(Status.valueOf(row.get("status").toString())); }
    private void active(Map<String, Object> session, Map<String, Object> row, Meta meta) {
        if (!meta.id().toString().equals(Objects.toString(session.get("current_run_id"), ""))
                || number(session, "generation") != meta.generation() || !instance.equals(row.get("lease_owner")))
            throw new AgentException("STORAGE_FENCED", "Task no longer owns the session");
    }
    private void audit(Meta m, String action, String outcome) {
        db.update("INSERT INTO agent_audit_event(tenant_id,event_id,actor_user_id,session_id,run_id,action,subject_type,subject_id,outcome,attributes_json,expires_at) VALUES(?,?,?,?,?,?,'RUN',?,?,?,?)",
                m.owner().tenantId(), UUID.randomUUID().toString(), m.owner().userId(), m.session().toString(), m.id().toString(), action,
                m.id().toString(), outcome, "{}", stamp(Instant.now().plus(Duration.ofDays(90))));
    }
    private void notifyOutbox(Meta m, long version) {
        db.update("INSERT INTO agent_outbox(tenant_id,outbox_id,event_id,destination,owner_user_id,session_id,run_id,payload_json) VALUES(?,?,?,'MEMORY_REFRESH',?,?,?,?)",
                m.owner().tenantId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), m.owner().userId(),
                m.session().toString(), m.id().toString(), encode(Map.of("version", version)));
    }
    private Created duplicate(String key, AccessScope owner, String fingerprint) {
        if (key == null) return null;
        var rows = db.queryForList("SELECT * FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND idempotency_key=?", owner.tenantId(), owner.userId(), key);
        if (rows.isEmpty()) return null;
        var stored = rows.get(0);
        if (!fingerprint.equals(stored.get("request_fingerprint"))) throw new AgentException("IDEMPOTENCY_CONFLICT", "Idempotency-Key has different content");
        return new Created(materialize(row(UUID.fromString(stored.get("run_id").toString()), owner, false), true), false);
    }
    @Override public Created create(String message, AccessScope owner, UUID requestedSession, String key, UUID reply) {
        String fingerprint = hash(encode(List.of(message, Objects.toString(requestedSession, ""), Objects.toString(reply, ""))));
        try {
            return write(() -> {
                Created duplicate = duplicate(key, owner, fingerprint); if (duplicate != null) return duplicate;
                Instant now = Instant.now(), expiry = now.plus(Duration.ofDays(30));
                UUID id = UUID.randomUUID(), sid = requestedSession == null ? UUID.randomUUID() : requestedSession;
                if (requestedSession == null) {
                    if (reply != null) throw new AgentException("INVALID_REQUEST", "sessionId is required with replyToRunId");
                    db.update("INSERT INTO agent_session(tenant_id,owner_user_id,session_id,expires_at) VALUES(?,?,?,?)", owner.tenantId(), owner.userId(), sid.toString(), stamp(expiry));
                }
                Map<String, Object> sess = session(sid, owner, true);
                String query = message;
                if (reply != null) {
                    Map<String, Object> origin = row(reply, owner, false);
                    if (!sid.toString().equals(origin.get("session_id")) || !"NEEDS_INPUT".equals(origin.get("status"))) throw new AgentException("CLARIFICATION_CONFLICT", "Clarification does not belong to this session");
                    var pending = db.queryForList("SELECT * FROM agent_clarification WHERE tenant_id=? AND run_id=? AND status='OPEN' AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE", owner.tenantId(), reply.toString());
                    if (pending.isEmpty()) throw new AgentException("CLARIFICATION_CONFLICT", "Clarification is expired or already resumed");
                    query = ClarificationInputs.combine(pending.get(0).get("original_query").toString(), message, LocalDate.parse(origin.get("business_date").toString()));
                }
                Object current = sess.get("current_run_id");
                if (current != null) {
                    Map<String, Object> previous = row(UUID.fromString(current.toString()), owner, true, false);
                    if (!terminal(previous)) finishLocked(meta(previous), previous, cancelled(snapshot(previous), StopReason.SUPERSEDED), "ABORT", Map.of("reason", "superseded"));
                }
                long turn = number(sess, "next_turn_seq"), generation = number(sess, "generation") + 1, version = number(sess, "history_version");
                Instant deadline = now.plus(timeout);
                Meta m = new Meta(id, sid, turn, generation, version, owner, message, query, now, deadline);
                RunState initial = new RunState(id, sid, turn, owner, message, query, now, deadline, RunBackend.MEMORY,
                        new com.tianmu.agent.runtime.events.InMemoryRunEventStore(), new InMemoryEvidenceStore(), false);
                initial.bindPersistedControl(maxCalls);
                db.update("INSERT INTO agent_run(tenant_id,owner_user_id,session_id,run_id,turn_seq,session_generation,base_history_version,history_before_turn,idempotency_key,request_fingerprint,request_json,access_scope_json,execution_query,snapshot_json,reply_to_run_id,business_date,business_timezone,deadline_at,created_at,events_expires_at,data_expires_at,lease_owner,lease_token,lease_until,last_event_seq,max_model_calls) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,1,?)",
                        owner.tenantId(), owner.userId(), sid.toString(), id.toString(), turn, generation, version, turn - 1, key, fingerprint,
                        encode(Map.of("message", message)), encode(owner), query, encode(initial.snapshot()), reply == null ? null : reply.toString(),
                        java.sql.Date.valueOf(initial.businessDate), "Asia/Shanghai", stamp(deadline), stamp(now), stamp(now.plus(Duration.ofDays(7))), stamp(expiry), instance, stamp(deadline), maxCalls);
                db.update("UPDATE agent_session SET generation=?,next_turn_seq=?,current_run_id=?,updated_at=?,expires_at=?,version=version+1 WHERE tenant_id=? AND session_id=?",
                        generation, turn + 1, id.toString(), stamp(now), stamp(expiry), owner.tenantId(), sid.toString());
                db.update("INSERT INTO agent_message(tenant_id,owner_user_id,session_id,run_id,message_id,message_seq,role,kind,content,content_sha256,expires_at) VALUES(?,?,?,?,?,?,'USER','QUERY',?,?,?)",
                        owner.tenantId(), owner.userId(), sid.toString(), id.toString(), UUID.randomUUID().toString(), turn * 2 - 1, message, hash(message), stamp(expiry));
                insertEvent(m, 1, "PROGRESS", Map.of("stage", "queued", "run_id", id.toString()), false, now.plus(Duration.ofDays(7)));
                if (reply != null) db.update("UPDATE agent_clarification SET status='RESUMED',resumed_by_run_id=?,resumed_at=UTC_TIMESTAMP(6),version=version+1 WHERE tenant_id=? AND run_id=? AND status='OPEN'", id.toString(), owner.tenantId(), reply.toString());
                audit(m, "RUN_CREATED", "QUEUED");
                return new Created(materialize(row(id, owner, false), false), true);
            });
        } catch (AgentException e) { throw e; }
    }
    @Override public RunState load(UUID id, AccessScope owner) {
        return read(() -> { var row = row(id, owner, false); session(UUID.fromString(row.get("session_id").toString()), owner, false); return materialize(row, true); });
    }
    private RunState materialize(Map<String, Object> row, boolean restored) {
        Meta m = meta(row);
        RunState run = new RunState(m.id(), m.session(), m.turn(), m.owner(), m.message(), m.query(), m.created(), m.deadline(), new Backend(m), new Events(m), new EvidenceRecords(m), restored);
        run.bindPersistedControl((int) number(row, "max_model_calls"));
        if (restored) run.restore(snapshot(row)); return run;
    }
    private final class Backend implements RunBackend {
        private final Meta meta;
        Backend(Meta meta) { this.meta = meta; }
        @Override public void save(RunState run) {
            Snapshot proposed = run.snapshot();
            Snapshot other = write(() -> {
                var sess = session(meta.session(), meta.owner(), true); var row = row(meta.id(), meta.owner(), true);
                if (terminal(row)) return snapshot(row); active(sess, row, meta);
                db.update("UPDATE agent_run SET status=?,snapshot_json=?,prepared_task_json=?,route=?,trace_id=?,step_count=?,preparation_calls=?,model_calls=?,max_model_calls=?,deadline_at=?,updated_at=UTC_TIMESTAMP(6),version=version+1 WHERE tenant_id=? AND run_id=?",
                        proposed.status().name(), encode(proposed), proposed.task() == null ? null : encode(proposed.task()), proposed.route() == null ? null : proposed.route().name(), proposed.traceId(), proposed.steps(), proposed.preparationCalls(), proposed.modelCalls(), run.modelCallLimit(), stamp(run.deadline()), meta.owner().tenantId(), meta.id().toString());
                return null;
            });
            if (other != null) { run.adoptTerminal(other); throw new AgentException("RUN_TERMINATED", "Task is already terminal"); }
        }
        @Override public void checkpoint(RunState run) {
            read(() -> {
                var sess = session(meta.session(), meta.owner(), false); var row = row(meta.id(), meta.owner(), false);
                if (terminal(row)) { run.adoptTerminal(snapshot(row)); return null; }
                active(sess, row, meta); return null;
            });
        }
        @Override public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
            Snapshot committed = write(() -> {
                var sess = session(meta.session(), meta.owner(), true); var row = row(meta.id(), meta.owner(), true);
                if (terminal(row)) return snapshot(row);
                active(sess, row, meta);
                if ((proposed.status() == Status.SUCCEEDED || proposed.status() == Status.NEEDS_INPUT) && !Instant.now().isBefore(run.deadline())) {
                    proposedExpired();
                }
                return finishLocked(meta, row, proposed, event, data);
            });
            signal(meta.id()); return committed;
        }
        private void proposedExpired() { throw new AgentException("DEADLINE_EXCEEDED", "Request deadline expired before commit"); }
    }
    private void insertEvent(Meta m, long sequence, String type, Object data, boolean done, Instant expiry) {
        String payload = encode(data);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65536) throw new AgentException("EVENT_TOO_LARGE", "Event payload exceeds its limit");
        db.update("INSERT INTO agent_run_event(tenant_id,owner_user_id,session_id,run_id,event_seq,event_type,payload_json,done_marker,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                m.owner().tenantId(), m.owner().userId(), m.session().toString(), m.id().toString(), sequence, type, payload, done ? 1 : null, stamp(expiry));
    }
    private Snapshot finishLocked(Meta m, Map<String, Object> row, Snapshot target, String event, Object data) {
        if (target.answer() != null) for (String id : target.answer().evidenceIds()) {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM agent_evidence e LEFT JOIN agent_artifact a ON a.tenant_id=e.tenant_id AND a.artifact_id=e.raw_artifact_id WHERE e.tenant_id=? AND e.owner_user_id=? AND e.run_id=? AND e.evidence_id=? AND e.expires_at>UTC_TIMESTAMP(6) AND (e.raw_artifact_id IS NULL OR a.status='READY')", Integer.class, m.owner().tenantId(), m.owner().userId(), m.id().toString(), id);
            if (count == null || count != 1) throw new AgentException("INVALID_EVIDENCE", "Answer evidence is not durably available");
        }
        long seq = number(row, "last_event_seq"); Instant now = Instant.now(), expiry = now.plus(Duration.ofDays(30));
        boolean visible = target.status() == Status.SUCCEEDED || target.status() == Status.NEEDS_INPUT;
        db.update("UPDATE agent_run SET status=?,stop_reason=?,error_code=?,snapshot_json=?,history_visible=?,route=?,terminal_at=?,updated_at=?,last_event_seq=?,data_expires_at=?,version=version+1 WHERE tenant_id=? AND run_id=?",
                target.status().name(), target.stopReason().name(), target.errorCode(), encode(target), visible ? 1 : 0, target.route() == null ? null : target.route().name(), stamp(now), stamp(now), seq + 2, stamp(expiry), m.owner().tenantId(), m.id().toString());
        if (visible) {
            String content = target.answer() != null ? target.answer().text() : target.clarification();
            db.update("INSERT INTO agent_message(tenant_id,owner_user_id,session_id,run_id,message_id,message_seq,role,kind,content,references_json,content_sha256,expires_at) VALUES(?,?,?,?,?,?,'ASSISTANT',?,?,?,?,?)",
                    m.owner().tenantId(), m.owner().userId(), m.session().toString(), m.id().toString(), UUID.randomUUID().toString(), m.turn() * 2,
                    target.answer() == null ? "CLARIFICATION" : "ANSWER", content, encode(target.answer() == null ? List.of() : target.answer().evidenceIds()), hash(content), stamp(expiry));
        }
        db.update("UPDATE agent_message SET expires_at=? WHERE tenant_id=? AND run_id=?", stamp(expiry), m.owner().tenantId(), m.id().toString());
        db.update("UPDATE agent_evidence SET expires_at=? WHERE tenant_id=? AND run_id=?", stamp(expiry), m.owner().tenantId(), m.id().toString());
        db.update("UPDATE agent_artifact SET expires_at=? WHERE tenant_id=? AND run_id=?", stamp(expiry), m.owner().tenantId(), m.id().toString());
        if (target.status() == Status.NEEDS_INPUT) {
            Map<String, Object> confirmed = ClarificationInputs.confirmed(m.query(), m.created().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
            List<String> missing = List.of("video_id", "date", "metric").stream().filter(field -> !confirmed.containsKey(field)).toList();
            db.update("INSERT INTO agent_clarification(tenant_id,owner_user_id,session_id,run_id,original_query,confirmed_fields_json,missing_fields_json,base_history_version,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    m.owner().tenantId(), m.owner().userId(), m.session().toString(), m.id().toString(), m.query(), encode(confirmed), encode(missing), m.historyVersion(), stamp(now.plus(Duration.ofHours(24))));
        }
        Instant eventsExpiry = instant(row, "events_expires_at");
        insertEvent(m, seq + 1, event, data, false, eventsExpiry);
        insertEvent(m, seq + 2, "DONE", Map.of("status", target.status().name(), "run_id", m.id().toString(), "stop_reason", target.stopReason().name()), true, eventsExpiry);
        db.update("UPDATE agent_session SET current_run_id=IF(current_run_id=?,NULL,current_run_id),history_version=history_version+?,updated_at=?,expires_at=?,version=version+1 WHERE tenant_id=? AND session_id=?",
                m.id().toString(), visible ? 1 : 0, stamp(now), stamp(expiry), m.owner().tenantId(), m.session().toString());
        audit(m, "RUN_COMMITTED", target.status().name());
        long history = db.queryForObject("SELECT history_version FROM agent_session WHERE tenant_id=? AND session_id=?", Long.class, m.owner().tenantId(), m.session().toString()); notifyOutbox(m, history);
        return target;
    }
    private Snapshot cancelled(Snapshot old, StopReason reason) {
        return terminalSnapshot(old, Status.CANCELLED, null, reason);
    }
    private Snapshot terminalSnapshot(Snapshot old, Status status, String code, StopReason reason) {
        return new Snapshot(old.runId(), status, old.createdAt(), old.steps(), null, null, code, old.traceId(), reason, old.modelCalls(), old.route(), old.faqId(), old.faqVersion(), old.preparation(), old.task(), old.preparationCalls(), old.totalModelCalls(), old.sessionId(), old.turnSeq());
    }
    private void signal(UUID id) { var handlers = listeners.get(id); if (handlers != null) handlers.values().forEach(r -> { try { r.run(); } catch (RuntimeException ignored) {} }); }
    private final class Events implements RunEventStore {
        private final Meta m; Events(Meta m) { this.m = m; }
        public void append(String type, Object data) {
            write(() -> {
                var sess = session(m.session(), m.owner(), true); var row = row(m.id(), m.owner(), true);
                if (terminal(row)) return null; active(sess, row, m);
                long seq = number(row, "last_event_seq"); if (seq >= 510) return null;
                insertEvent(m, seq + 1, type, data, false, instant(row, "events_expires_at"));
                db.update("UPDATE agent_run SET last_event_seq=? WHERE tenant_id=? AND run_id=?", seq + 1, m.owner().tenantId(), m.id().toString()); return null;
            }); signal(m.id());
        }
        public void finish(String type, Object data, Object done) { throw new IllegalStateException("Persistent terminal events require the run transaction"); }
        public Batch after(long cursor) {
            return read(() -> {
                session(m.session(), m.owner(), false); var row = row(m.id(), m.owner(), false); long last = number(row, "last_event_seq");
                if (cursor < 0 || cursor > last) throw new AgentException("INVALID_CURSOR", "Invalid event cursor");
                if (!Instant.now().isBefore(instant(row, "events_expires_at"))) throw new AgentException("EVENTS_EXPIRED", "Read the final Snapshot; event retention expired");
                List<Event> events = db.query("SELECT event_seq,event_type,payload_json,created_at FROM agent_run_event WHERE tenant_id=? AND run_id=? AND event_seq>? ORDER BY event_seq LIMIT 200",
                        (rs, n) -> new Event(rs.getLong(1), rs.getString(2), decode(rs.getString(3), Object.class), rs.getTimestamp(4).toInstant()), m.owner().tenantId(), m.id().toString(), cursor);
                long tail = events.isEmpty() ? cursor : events.get(events.size() - 1).sequence();
                return new Batch(events, terminal(row) && tail == last, last);
            });
        }
        public Registration onChange(Runnable action) {
            UUID key = UUID.randomUUID(); listeners.computeIfAbsent(m.id(), ignored -> new ConcurrentHashMap<>()).put(key, action);
            return () -> { var handlers = listeners.get(m.id()); if (handlers != null) { handlers.remove(key); if (handlers.isEmpty()) listeners.remove(m.id(), handlers); } };
        }
    }
    private final class EvidenceRecords implements EvidenceStore {
        private final Meta m; EvidenceRecords(Meta m) { this.m = m; }
        public Evidence get(String id) {
            return write(() -> {
                session(m.session(), m.owner(), false);
                var rows = db.queryForList("SELECT facts_json FROM agent_evidence WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND evidence_id=? AND expires_at>UTC_TIMESTAMP(6)", m.owner().tenantId(), m.owner().userId(), m.id().toString(), id);
                if (rows.isEmpty()) return null; audit(m, "EVIDENCE_READ", "AUTHORIZED");
                return new Evidence(id, m.id(), m.owner(), decode(rows.get(0).get("facts_json"), MetricData.class));
            });
        }
        public void put(Evidence value) {
            if (!value.runId().equals(m.id()) || !value.owner().equals(m.owner())) throw new AgentException("FORBIDDEN", "Evidence scope mismatch");
            byte[] body = encode(value.data()).getBytes(StandardCharsets.UTF_8); String digest = FileObjectStore.hash(body);
            String artifact = "ar_" + UUID.randomUUID(), object = artifact + ".json";
            write(() -> {
                var sess = session(m.session(), m.owner(), true); var row = row(m.id(), m.owner(), true); active(sess, row, m);
                if (terminal(row)) throw new AgentException("RUN_TERMINATED", "Evidence cannot be added after completion");
                db.update("INSERT INTO agent_artifact(tenant_id,owner_user_id,session_id,run_id,artifact_id,object_key,media_type,expires_at) VALUES(?,?,?,?,?,?,'application/json',?)",
                        m.owner().tenantId(), m.owner().userId(), m.session().toString(), m.id().toString(), artifact, object, stamp(Instant.now().plus(Duration.ofDays(30)))); return null;
            });
            try {
            objects.put(object, body);
            write(() -> {
                var sess = session(m.session(), m.owner(), true); var row = row(m.id(), m.owner(), true); active(sess, row, m);
                if (terminal(row)) throw new AgentException("RUN_TERMINATED", "Evidence cannot be added after completion");
                db.update("UPDATE agent_artifact SET status='READY',content_sha256=?,size_bytes=?,ready_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND artifact_id=?", digest, body.length, m.owner().tenantId(), artifact);
                db.update("INSERT INTO agent_evidence(tenant_id,owner_user_id,session_id,run_id,evidence_id,query_fingerprint,facts_json,content_sha256,raw_artifact_id,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        m.owner().tenantId(), m.owner().userId(), m.session().toString(), m.id().toString(), value.id(), hash(encode(Map.of("video", value.data().videoId(), "date", value.data().date(), "metric", value.data().metric()))), new String(body, StandardCharsets.UTF_8), digest, artifact, stamp(Instant.now().plus(Duration.ofDays(30))));
                audit(m, "EVIDENCE_REGISTERED", "READY"); return null;
            });
            } catch (RuntimeException failure) {
                try { objects.delete(object); } catch (RuntimeException ignored) { /* PENDING metadata remains for GC. */ }
                throw failure;
            }
        }

        public Map<String, Evidence> snapshot() {
            return read(() -> {
                Map<String, Evidence> result = new LinkedHashMap<>();
                db.query("SELECT evidence_id,facts_json FROM agent_evidence WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND expires_at>UTC_TIMESTAMP(6)", rs -> {
                    String id = rs.getString(1); result.put(id, new Evidence(id, m.id(), m.owner(), decode(rs.getString(2), MetricData.class)));
                }, m.owner().tenantId(), m.owner().userId(), m.id().toString()); return Map.copyOf(result);
            });
        }
    }
    private List<MemoryTypes.Message> history(UUID sid, AccessScope owner, long before) {
        List<Map<String, Object>> turns = db.queryForList("SELECT run_id,access_scope_json FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND history_visible=1 AND turn_seq<=? AND data_expires_at>UTC_TIMESTAMP(6) ORDER BY turn_seq DESC LIMIT 10", owner.tenantId(), owner.userId(), sid.toString(), before);
        List<MemoryTypes.Message> messages = new ArrayList<>();
        for (var turn : turns) if (owner.videoIds().containsAll(decode(turn.get("access_scope_json"), AccessScope.class).videoIds()))
            messages.addAll(db.query("SELECT message_id,run_id,message_seq,role,content,created_at FROM agent_message WHERE tenant_id=? AND owner_user_id=? AND run_id=? ORDER BY message_seq",
                    (rs, n) -> new MemoryTypes.Message(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), owner.tenantId(), owner.userId(), turn.get("run_id")));
        return messages.stream().sorted(Comparator.comparingLong(MemoryTypes.Message::sequence)).toList();
    }
    @Override public MemoryTypes.View memory(RunState run) {
        return read(() -> {
            var sess = session(run.sessionId, run.owner, false); var stored = row(run.id, run.owner, false);
            if (terminal(stored)) throw new AgentException("RUN_TERMINATED", "Task is terminal");
            long version = number(stored, "base_history_version"), before = number(stored, "history_before_turn");
            String key = cacheKey(run.owner, run.sessionId);
            var cached = cache.get(key, version, before);
            List<MemoryTypes.Message> messages;
            if (cached == null) { messages = history(run.sessionId, run.owner, before); cache.put(key, new RedisMemoryCache.Window(version, before, cacheExpiry(run.sessionId, run.owner, before), messages)); }
            else messages = cached.messages();
            return memoryBuilder.build(version, messages);
        });
    }
    private String cacheKey(AccessScope owner, UUID session) { return RedisMemoryCache.key(owner.tenantId(), owner.userId() + ":" + hash(encode(new TreeSet<>(owner.videoIds()))), session); }
    private Instant cacheExpiry(UUID sid, AccessScope owner, long before) {
        Timestamp oldest = db.queryForObject("SELECT MIN(data_expires_at) FROM (SELECT data_expires_at FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND history_visible=1 AND turn_seq<=? AND data_expires_at>UTC_TIMESTAMP(6) ORDER BY turn_seq DESC LIMIT 10) recent", Timestamp.class, owner.tenantId(), owner.userId(), sid.toString(), before);
        Instant ttl = Instant.now().plus(Duration.ofHours(24)); return oldest != null && oldest.toInstant().isBefore(ttl) ? oldest.toInstant() : ttl;
    }
    @Override public List<MemoryTypes.Message> messages(UUID sid, AccessScope owner, long before) {
        return read(() -> {
            session(sid, owner, false); if (before < 1) throw new AgentException("INVALID_CURSOR", "Invalid message cursor");
            var rows = db.queryForList("SELECT m.*,r.access_scope_json FROM agent_message m JOIN agent_run r ON r.tenant_id=m.tenant_id AND r.run_id=m.run_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.session_id=? AND m.message_seq<? AND r.data_expires_at>UTC_TIMESTAMP(6) ORDER BY m.message_seq DESC LIMIT 50", owner.tenantId(), owner.userId(), sid.toString(), before);
            List<MemoryTypes.Message> result = new ArrayList<>();
            for (var row : rows) if (owner.videoIds().containsAll(decode(row.get("access_scope_json"), AccessScope.class).videoIds()))
                result.add(new MemoryTypes.Message(UUID.fromString(row.get("message_id").toString()), UUID.fromString(row.get("run_id").toString()), number(row,"message_seq"), row.get("role").toString(), row.get("content").toString(), instant(row,"created_at")));
            return result.stream().sorted(Comparator.comparingLong(MemoryTypes.Message::sequence)).toList();
        });
    }
    public List<Map<String, Object>> artifacts(UUID runId, AccessScope owner) {
        return read(() -> { load(runId, owner); return db.queryForList("SELECT artifact_id AS artifactId,media_type AS mediaType,size_bytes AS sizeBytes,content_sha256 AS sha256 FROM agent_artifact WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND status='READY' AND expires_at>UTC_TIMESTAMP(6)", owner.tenantId(), owner.userId(), runId.toString()); });
    }
    public byte[] artifact(UUID runId, String id, AccessScope owner) {
        Map<String, Object> row = write(() -> {
            var source = row(runId, owner, false); session(UUID.fromString(source.get("session_id").toString()), owner, false);
            var matches = db.queryForList("SELECT * FROM agent_artifact WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND artifact_id=? AND status='READY' AND expires_at>UTC_TIMESTAMP(6)", owner.tenantId(), owner.userId(), runId.toString(), id);
            if (matches.isEmpty()) throw new AgentException("RESULT_NOT_FOUND", "Artifact not found"); audit(meta(source), "ARTIFACT_READ", "AUTHORIZED"); return matches.get(0);
        });
        return objects.get(row.get("object_key").toString(), number(row, "size_bytes"), row.get("content_sha256").toString());
    }
    @Override public void refresh(RunState run) {
        Snapshot current = read(() -> snapshot(row(run.id, run.owner, false))); run.adoptTerminal(current);
    }
    private void recoverInterrupted() {
        var interrupted = read(() -> db.queryForList("SELECT r.* FROM agent_run r JOIN agent_session s ON s.tenant_id=r.tenant_id AND s.session_id=r.session_id WHERE r.status='RUNNING' AND r.data_expires_at>UTC_TIMESTAMP(6) AND s.status='OPEN' AND s.expires_at>UTC_TIMESTAMP(6)"));
        for (var old : interrupted) write(() -> {
            Meta m = meta(old); session(m.session(), m.owner(), true); var row = row(m.id(), m.owner(), true);
            if (!terminal(row)) finishLocked(m, row, terminalSnapshot(snapshot(row), Status.FAILED, "RUNNER_LOST", StopReason.ERROR), "ERROR", Map.of("code", "RUNNER_LOST")); return null;
        });
        write(() -> {
            db.update("UPDATE agent_run SET lease_owner=?,lease_token=lease_token+1 WHERE status='QUEUED'", instance); return null;
        });
    }
    @Override public List<RunState> recoverQueued() {
        return read(() -> db.queryForList("SELECT r.* FROM agent_run r JOIN agent_session s ON s.tenant_id=r.tenant_id AND s.session_id=r.session_id WHERE r.status='QUEUED' AND r.lease_owner=? AND r.data_expires_at>UTC_TIMESTAMP(6) AND s.status='OPEN' AND s.expires_at>UTC_TIMESTAMP(6) ORDER BY r.created_at LIMIT 32", instance).stream().map(row -> materialize(row, false)).toList());
    }
    @Scheduled(fixedDelay=1000)
    public void deliverOutbox() {
        try {
            List<Map<String, Object>> batch = write(() -> {
                db.update("UPDATE agent_outbox SET status='PENDING',claim_token=NULL,lease_owner=NULL,lease_until=NULL WHERE status='INFLIGHT' AND lease_until<UTC_TIMESTAMP(6)");
                var records = db.queryForList("SELECT * FROM agent_outbox WHERE status='PENDING' AND available_at<=UTC_TIMESTAMP(6) ORDER BY available_at,outbox_id LIMIT 20 FOR UPDATE SKIP LOCKED");
                for (var record : records) { String claim = UUID.randomUUID().toString(); record.put("claim", claim);
                    db.update("UPDATE agent_outbox SET status='INFLIGHT',claim_token=?,lease_owner=?,lease_until=?,attempts=attempts+1 WHERE tenant_id=? AND outbox_id=?", claim, instance, stamp(Instant.now().plusSeconds(15)), record.get("tenant_id"), record.get("outbox_id")); }
                return records;
            });
            for (var record : batch) {
                try {
                    warm(record);
                    write(() -> { db.update("UPDATE agent_outbox SET status='SENT',sent_at=UTC_TIMESTAMP(6),claim_token=NULL,lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND outbox_id=? AND claim_token=?", record.get("tenant_id"), record.get("outbox_id"), record.get("claim")); return null; });
                } catch (Exception e) { write(() -> { db.update("UPDATE agent_outbox SET status=IF(attempts>=10,'DEAD','PENDING'),available_at=?,last_error_code='CACHE_REFRESH_FAILED',claim_token=NULL,lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND outbox_id=? AND claim_token=?", stamp(Instant.now().plusSeconds(5)), record.get("tenant_id"), record.get("outbox_id"), record.get("claim")); return null; }); }
            }
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("outbox_unavailable type={}", e.getClass().getSimpleName()); }
    }
    private void warm(Map<String, Object> record) {
        if (!"MEMORY_REFRESH".equals(record.get("destination"))) throw new AgentException("OUTBOX_TARGET_UNSUPPORTED", "No registered handler for outbox target");
        // The cache is scoped by the saved authorization snapshot, preventing a narrowed scope from using a broader window.
        var runs = read(() -> db.queryForList("SELECT * FROM agent_run WHERE tenant_id=? AND run_id=?", record.get("tenant_id"), record.get("run_id")));
        if (runs.isEmpty()) return; Meta m = meta(runs.get(0));
        Map<String, Object> sess;
        try { sess = read(() -> session(m.session(), m.owner(), false)); }
        catch (AgentException e) { if (e.code().equals("SESSION_NOT_FOUND")) return; throw e; }
        long version = number(sess, "history_version"), before = number(sess, "next_turn_seq") - 1;
        List<MemoryTypes.Message> messages = read(() -> history(m.session(), m.owner(), before));
        if (!cache.put(cacheKey(m.owner(), m.session()), new RedisMemoryCache.Window(version, before, cacheExpiry(m.session(), m.owner(), before), messages)))
            throw new AgentException("CACHE_UNAVAILABLE", "Memory refresh will retry");
        signal(m.id());
    }
    public void deleteSession(UUID sid, AccessScope owner) {
        write(() -> {
            var sess = session(sid, owner, true);
            Object active = sess.get("current_run_id");
            if (active != null) {
                var current = row(UUID.fromString(active.toString()), owner, true);
                if (!terminal(current)) finishLocked(meta(current), current, cancelled(snapshot(current), StopReason.USER_CANCELLED), "ABORT", Map.of("reason", "user_cancelled"));
            }
            db.update("UPDATE agent_session SET status='DELETING',generation=generation+1,session_epoch=session_epoch+1,history_version=history_version+1,purge_requested_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND session_id=?", owner.tenantId(), sid.toString());
            db.update("INSERT INTO agent_audit_event(tenant_id,event_id,actor_user_id,session_id,action,subject_type,subject_id,outcome,attributes_json,expires_at) VALUES(?,?,?,?,'SESSION_DELETE_REQUESTED','SESSION',?,'DELETING','{}',?)",
                    owner.tenantId(), UUID.randomUUID().toString(), owner.userId(), sid.toString(), sid.toString(), stamp(Instant.now().plus(Duration.ofDays(90))));
            return null;
        });
        listeners.values().forEach(actions -> actions.values().forEach(action -> { try { action.run(); } catch (RuntimeException ignored) {} }));
    }
    @Scheduled(fixedDelay=5000)
    public void garbageCollect() {
        try {
            // Files are removed outside transactions. DELETING/expired records are already unreadable.
            var sessions = read(() -> db.queryForList("SELECT tenant_id,session_id FROM agent_session WHERE status='DELETING' OR expires_at<UTC_TIMESTAMP(6) LIMIT 10"));
            for (var source : sessions) {
                String tenant = source.get("tenant_id").toString(), sid = source.get("session_id").toString();
                write(() -> { db.update("UPDATE agent_session SET status='DELETING',generation=generation+1,history_version=history_version+1 WHERE tenant_id=? AND session_id=? AND status<>'DELETING'", tenant, sid); return null; });
                var artifacts = read(() -> db.queryForList("SELECT object_key FROM agent_artifact WHERE tenant_id=? AND session_id=?", tenant, sid));
                for (var artifact : artifacts) objects.delete(artifact.get("object_key").toString());
                var oldRuns = read(() -> db.queryForList("SELECT * FROM agent_run WHERE tenant_id=? AND session_id=?", tenant, sid));
                for (var old : oldRuns) { Meta m = meta(old); cache.evict(cacheKey(m.owner(), m.session())); }
                write(() -> {
                    db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND session_id=? FOR UPDATE", tenant, sid);
                    for (String table : List.of("agent_clarification", "agent_evidence", "agent_artifact", "agent_message", "agent_run_event", "agent_run"))
                        db.update("DELETE FROM " + table + " WHERE tenant_id=? AND session_id=?", tenant, sid);
                    db.update("DELETE FROM agent_session WHERE tenant_id=? AND session_id=? AND status='DELETING'", tenant, sid); return null;
                });
            }
            write(() -> {
                db.update("DELETE FROM agent_run_event WHERE expires_at<UTC_TIMESTAMP(6) LIMIT 500");
                db.update("DELETE FROM agent_clarification WHERE expires_at<UTC_TIMESTAMP(6) LIMIT 100");
                db.update("DELETE FROM agent_audit_event WHERE expires_at<UTC_TIMESTAMP(6) LIMIT 500");
                db.update("DELETE FROM agent_outbox WHERE status='SENT' AND sent_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 7 DAY) LIMIT 500"); return null;
            });
            var expired = read(() -> db.queryForList("SELECT * FROM agent_run WHERE data_expires_at<UTC_TIMESTAMP(6) AND deadline_at<UTC_TIMESTAMP(6) LIMIT 20"));
            for (var old : expired) {
                Meta m = meta(old);
                var artifacts = read(() -> db.queryForList("SELECT object_key FROM agent_artifact WHERE tenant_id=? AND run_id=?", m.owner().tenantId(), m.id().toString()));
                for (var artifact : artifacts) objects.delete(artifact.get("object_key").toString());
                write(() -> {
                    db.queryForList("SELECT session_id FROM agent_session WHERE tenant_id=? AND session_id=? FOR UPDATE", m.owner().tenantId(), m.session().toString());
                    db.update("DELETE FROM agent_clarification WHERE tenant_id=? AND (run_id=? OR resumed_by_run_id=?)", m.owner().tenantId(), m.id().toString(), m.id().toString());
                    for (String table : List.of("agent_evidence", "agent_artifact", "agent_message", "agent_run_event", "agent_run"))
                        db.update("DELETE FROM " + table + " WHERE tenant_id=? AND run_id=?", m.owner().tenantId(), m.id().toString());
                    db.update("UPDATE agent_session SET history_version=history_version+1 WHERE tenant_id=? AND session_id=?", m.owner().tenantId(), m.session().toString()); return null;
                }); cache.evict(cacheKey(m.owner(), m.session()));
            }
            var pending = read(() -> db.queryForList("SELECT tenant_id,artifact_id,object_key FROM agent_artifact WHERE status='PENDING' AND created_at<DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) LIMIT 20"));
            for (var artifact : pending) {
                objects.delete(artifact.get("object_key").toString());
                write(() -> { db.update("UPDATE agent_artifact SET status='FAILED' WHERE tenant_id=? AND artifact_id=? AND status='PENDING'", artifact.get("tenant_id"), artifact.get("artifact_id")); return null; });
            }
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("storage_gc_failed type={}", e.getClass().getSimpleName()); }
    }
    @Override public void close() {
        try { synchronized (this) { try (PreparedStatement statement = writer.prepareStatement("SELECT RELEASE_LOCK(?)")) { statement.setString(1, writerLock); statement.execute(); } writer.close(); } }
        catch (SQLException ignored) {}
    }
}
