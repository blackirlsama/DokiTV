-- DESIGN EXAMPLES ONLY. This is not an executable migration or an application.
-- :named_parameters and assertion branches must be implemented by the adapter.
-- Every query uses server-authenticated scope, not caller-supplied owner IDs.
-- UTC connections; validate bounded limits and payload sizes in the service.

-- 1. Memory snapshot: latest 10 complete visible turns (up to 20 messages).
-- :before_turn and :history_version were captured when this run was accepted.
-- Cached snapshots additionally check epoch/version/validUntil in the service.
SELECT m.message_id, m.run_id, m.message_seq, m.role, m.kind,
       m.content, m.references_json, m.expires_at
FROM (
    SELECT r.tenant_id, r.owner_user_id, r.session_id, r.run_id
    FROM agent_run r
    WHERE r.tenant_id = :tenant_id
      AND r.owner_user_id = :owner_user_id
      AND r.session_id = :session_id
      AND r.history_visible = 1
      AND r.turn_seq <= :before_turn
      AND r.data_expires_at > :snapshot_utc
    ORDER BY r.turn_seq DESC
    LIMIT 10
) visible
JOIN agent_message m ON m.tenant_id = visible.tenant_id
    AND m.owner_user_id = visible.owner_user_id
    AND m.session_id = visible.session_id
    AND m.run_id = visible.run_id
ORDER BY m.message_seq ASC;

-- 2. SSE replay after ownership, retention and cursor checks.
-- A result limit bounds one database batch; it does not truncate the stream.
SELECT event_seq, event_type, payload_json, created_at
FROM agent_run_event
WHERE tenant_id = :tenant_id AND owner_user_id = :owner_user_id
  AND session_id = :session_id AND run_id = :run_id
  AND event_seq > :after_sequence
ORDER BY event_seq ASC
LIMIT 200;

-- 3. Candidate discovery only. No model/tool call happens under a DB lock.
-- For each candidate, first lock its session, then its run; check current_run_id,
-- generation, status and deadline before assigning/incrementing the lease token.
-- Expired RUNNING tasks use the RUNNER_LOST path, not blind model re-execution.
SELECT tenant_id, owner_user_id, session_id, run_id
FROM agent_run
WHERE status = 'QUEUED' AND deadline_at > CURRENT_TIMESTAMP(6)
ORDER BY created_at, run_id
LIMIT 100;

-- 4. Normal terminal commit SKELETON. All inserts/updates below share one TX.
START TRANSACTION;

SELECT status, current_run_id, generation, history_version, version
FROM agent_session
WHERE tenant_id = :tenant_id AND owner_user_id = :owner_user_id
  AND session_id = :session_id
FOR UPDATE;

SELECT status, version, lease_owner, lease_token, lease_until,
       session_generation, deadline_at, last_event_seq
FROM agent_run
WHERE tenant_id = :tenant_id AND owner_user_id = :owner_user_id
  AND session_id = :session_id AND run_id = :run_id
FOR UPDATE;

-- APPLICATION ASSERTIONS BEFORE ANY WRITE:
-- session OPEN, current_run_id matches, generation matches;
-- run active, worker owner/token/lease valid, deadline valid;
-- answer, evidence ownership and READY artifacts validated;
-- :last_sequence and :history_version_after come from the locked rows.
-- If already terminal, return its persisted Snapshot; do not publish again.
-- If an assertion fails, ROLLBACK; zero changed rows is not success.

UPDATE agent_run
SET status = :terminal_status, route = :route, history_visible = 1,
    stop_reason = :stop_reason, terminal_at = CURRENT_TIMESTAMP(6),
    last_event_seq = :last_sequence + 2,
    version = version + 1, updated_at = CURRENT_TIMESTAMP(6),
    data_expires_at = :data_expires_at
WHERE tenant_id = :tenant_id AND owner_user_id = :owner_user_id
  AND session_id = :session_id AND run_id = :run_id
  AND status = 'RUNNING' AND version = :expected_run_version
  AND session_generation = :generation
  AND lease_owner = :lease_owner AND lease_token = :lease_token
  AND lease_until > CURRENT_TIMESTAMP(6)
  AND deadline_at > CURRENT_TIMESTAMP(6);
-- ASSERT affected rows = 1, otherwise ROLLBACK.
-- :terminal_status is SUCCEEDED or NEEDS_INPUT in this normal-visible path.
-- Cancel/failure/supersede use a different admission rule and history_visible=0.

INSERT INTO agent_message
    (tenant_id, owner_user_id, session_id, run_id, message_id,
     message_seq, role, kind, content, references_json, content_sha256, expires_at)
VALUES
    (:tenant_id, :owner_user_id, :session_id, :run_id, :assistant_message_id,
     :turn_seq * 2, 'ASSISTANT', :message_kind, :answer_or_question,
     :references_json, :content_sha256, :data_expires_at);

UPDATE agent_message SET expires_at = :data_expires_at
WHERE tenant_id = :tenant_id AND owner_user_id = :owner_user_id
  AND session_id = :session_id AND run_id = :run_id AND role = 'USER';
-- Extend this run's evidence/artifact expiry consistently when needed.
-- NEEDS_INPUT also inserts agent_clarification in this transaction.

INSERT INTO agent_run_event
    (tenant_id, owner_user_id, session_id, run_id, event_seq,
     event_type, payload_json, done_marker, expires_at)
VALUES
    (:tenant_id, :owner_user_id, :session_id, :run_id, :last_sequence + 1,
     :terminal_event_type, :terminal_payload_json, NULL, :events_expires_at),
    (:tenant_id, :owner_user_id, :session_id, :run_id, :last_sequence + 2,
     'DONE', :done_payload_json, 1, :events_expires_at);

UPDATE agent_session
SET current_run_id = NULL, history_version = history_version + 1,
    version = version + 1, updated_at = CURRENT_TIMESTAMP(6),
    expires_at = :session_expires_at
WHERE tenant_id = :tenant_id AND owner_user_id = :owner_user_id
  AND session_id = :session_id AND current_run_id = :run_id
  AND generation = :generation;
-- ASSERT affected rows = 1, otherwise ROLLBACK.

INSERT INTO agent_audit_event
    (tenant_id, event_id, actor_user_id, session_id, run_id, action,
     subject_type, subject_id, outcome, attributes_json, expires_at)
VALUES
    (:tenant_id, :audit_event_id, :owner_user_id, :session_id, :run_id,
     'RUN_COMMITTED', 'RUN', :run_id, :terminal_status,
     :redacted_audit_attributes_json, :audit_expires_at);

INSERT INTO agent_outbox
    (tenant_id, outbox_id, event_id, destination, owner_user_id,
     session_id, run_id, payload_json)
VALUES
    (:tenant_id, :outbox_id, :notification_event_id, 'MEMORY_INVALIDATE',
     :owner_user_id, :session_id, :run_id, :version_only_notification_json);
-- Insert RUN_NOTIFY/AUDIT_EXPORT as separate target-specific outbox rows if enabled.
COMMIT;
-- Only now update the local projection and signal SSE. No Redis/Kafka/HTTP in TX.

-- 5. Outbox claim: the network send happens AFTER this short transaction.
START TRANSACTION;
SELECT tenant_id, outbox_id, event_id, destination, payload_json
FROM agent_outbox
WHERE status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP(6)
ORDER BY available_at, outbox_id
LIMIT 100
FOR UPDATE SKIP LOCKED;

-- Adapter updates only the selected rows with safely bound IDs; example one row:
UPDATE agent_outbox
SET status = 'INFLIGHT', lease_owner = :dispatcher_id, claim_token = :claim_token,
    lease_until = :lease_until, attempts = attempts + 1
WHERE tenant_id = :tenant_id AND outbox_id = :outbox_id AND status = 'PENDING';
COMMIT;

-- After successful external send, mark the matching claim SENT.
UPDATE agent_outbox
SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(6),
    lease_owner = NULL, claim_token = NULL, lease_until = NULL
WHERE tenant_id = :tenant_id AND outbox_id = :outbox_id
  AND status = 'INFLIGHT' AND claim_token = :claim_token
  AND lease_until > CURRENT_TIMESTAMP(6);
-- Lost claim or unknown send outcome can cause a duplicate external delivery.
-- Consumers deduplicate by event_id. Recovery reclaims expired INFLIGHT rows,
-- backs off failures, and alerts on DEAD records; never delete unsent work as TTL GC.
