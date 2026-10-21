-- Tianmu Agent storage DESIGN DRAFT, 2026-10-21.
-- V1 executable migration; Flyway records checksum and application state.
-- Review and integration-test in a separate Agent database before use.
-- Connections and DATETIME(6) values use UTC.
-- updated_at is explicitly maintained by application transactions.
-- No database selection, credentials, DROP statements or runtime wiring.
-- Audit/outbox intentionally have no FK to expiring business rows.
-- Global worker/GC indexes require restricted service-account access.

CREATE TABLE agent_session (
    tenant_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    session_epoch BIGINT UNSIGNED NOT NULL DEFAULT 1,
    generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    next_turn_seq BIGINT UNSIGNED NOT NULL DEFAULT 1,
    history_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    current_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    purge_requested_at DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, session_id),
    UNIQUE KEY uk_session_owner (tenant_id, owner_user_id, session_id),
    KEY ix_session_list (tenant_id, owner_user_id, status, updated_at DESC, session_id),
    KEY ix_session_expiry (status, expires_at),
    CONSTRAINT ck_session_status CHECK (status IN ('OPEN', 'CLOSED', 'DELETING')),
    CONSTRAINT ck_session_next_turn CHECK (next_turn_seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_run (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_seq BIGINT UNSIGNED NOT NULL,
    session_generation BIGINT UNSIGNED NOT NULL,
    base_history_version BIGINT UNSIGNED NOT NULL,
    history_before_turn BIGINT UNSIGNED NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json JSON NOT NULL,
    access_scope_json JSON NOT NULL,
    execution_query TEXT NOT NULL,
    snapshot_json JSON NOT NULL,
    prepared_task_json JSON NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    reply_to_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    business_date DATE NOT NULL,
    business_timezone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    route VARCHAR(16) NULL,
    history_visible TINYINT UNSIGNED NOT NULL DEFAULT 0,
    stop_reason VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    trace_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    step_count INT UNSIGNED NOT NULL DEFAULT 0,
    preparation_calls INT UNSIGNED NOT NULL DEFAULT 0,
    model_calls INT UNSIGNED NOT NULL DEFAULT 0,
    max_model_calls INT UNSIGNED NOT NULL DEFAULT 16,
    last_event_seq BIGINT UNSIGNED NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) NULL,
    lease_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    deadline_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    terminal_at DATETIME(6) NULL,
    events_expires_at DATETIME(6) NOT NULL,
    data_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    UNIQUE KEY uk_run_owner (tenant_id, owner_user_id, session_id, run_id),
    UNIQUE KEY uk_run_turn (tenant_id, session_id, turn_seq),
    UNIQUE KEY uk_run_idempotency (tenant_id, owner_user_id, idempotency_key),
    KEY ix_run_history (tenant_id, session_id, history_visible, turn_seq DESC),
    KEY ix_run_dispatch (status, lease_until, created_at),
    KEY ix_run_expiry (data_expires_at),
    CONSTRAINT fk_run_session FOREIGN KEY (tenant_id, owner_user_id, session_id)
        REFERENCES agent_session (tenant_id, owner_user_id, session_id),
    CONSTRAINT ck_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'NEEDS_INPUT', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_run_route CHECK (route IS NULL OR route IN ('FAQ', 'CLARIFY', 'HARNESS')),
    CONSTRAINT ck_run_history CHECK (history_visible IN (0, 1) AND (history_visible = 0 OR status IN ('SUCCEEDED', 'NEEDS_INPUT'))),
    CONSTRAINT ck_run_turn CHECK (turn_seq > 0 AND history_before_turn < turn_seq),
    CONSTRAINT ck_run_budget CHECK (max_model_calls > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_message (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_seq BIGINT UNSIGNED NOT NULL,
    role VARCHAR(16) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    references_json JSON NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, message_id),
    UNIQUE KEY uk_message_sequence (tenant_id, session_id, message_seq),
    UNIQUE KEY uk_message_run_role (tenant_id, run_id, role),
    KEY ix_message_expiry (expires_at),
    CONSTRAINT fk_message_run FOREIGN KEY (tenant_id, owner_user_id, session_id, run_id)
        REFERENCES agent_run (tenant_id, owner_user_id, session_id, run_id),
    CONSTRAINT ck_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_message_kind CHECK ((role = 'USER' AND kind = 'QUERY') OR (role = 'ASSISTANT' AND kind IN ('ANSWER', 'CLARIFICATION'))),
    CONSTRAINT ck_message_content CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 8000),
    CONSTRAINT ck_message_sequence CHECK (message_seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_run_event (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_seq BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    done_marker TINYINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, run_id, event_seq),
    UNIQUE KEY uk_event_done (tenant_id, run_id, done_marker),
    KEY ix_event_expiry (expires_at),
    CONSTRAINT fk_event_run FOREIGN KEY (tenant_id, owner_user_id, session_id, run_id)
        REFERENCES agent_run (tenant_id, owner_user_id, session_id, run_id),
    CONSTRAINT ck_event_sequence CHECK (event_seq > 0),
    CONSTRAINT ck_event_type CHECK (event_type IN ('PROGRESS', 'ANSWER', 'CLARIFICATION', 'ERROR', 'ABORT', 'DONE')),
    CONSTRAINT ck_event_done CHECK ((event_type = 'DONE' AND done_marker IS NOT NULL AND done_marker = 1)
        OR (event_type <> 'DONE' AND done_marker IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_artifact (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    artifact_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    size_bytes BIGINT UNSIGNED NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ready_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, artifact_id),
    UNIQUE KEY uk_artifact_object (tenant_id, object_key),
    UNIQUE KEY uk_artifact_run (tenant_id, owner_user_id, run_id, artifact_id),
    KEY ix_artifact_gc (status, expires_at),
    CONSTRAINT fk_artifact_run FOREIGN KEY (tenant_id, owner_user_id, session_id, run_id)
        REFERENCES agent_run (tenant_id, owner_user_id, session_id, run_id),
    CONSTRAINT ck_artifact_status CHECK (status IN ('PENDING', 'READY', 'DELETING', 'DELETED', 'FAILED')),
    CONSTRAINT ck_artifact_ready CHECK (status <> 'READY' OR (content_sha256 IS NOT NULL AND size_bytes IS NOT NULL AND ready_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_evidence (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    query_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    facts_json JSON NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    raw_artifact_id VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, evidence_id),
    KEY ix_evidence_run (tenant_id, owner_user_id, session_id, run_id),
    KEY ix_evidence_expiry (expires_at),
    CONSTRAINT fk_evidence_run FOREIGN KEY (tenant_id, owner_user_id, session_id, run_id)
        REFERENCES agent_run (tenant_id, owner_user_id, session_id, run_id),
    CONSTRAINT fk_evidence_artifact FOREIGN KEY (tenant_id, owner_user_id, run_id, raw_artifact_id)
        REFERENCES agent_artifact (tenant_id, owner_user_id, run_id, artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_clarification (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_query TEXT NOT NULL,
    confirmed_fields_json JSON NOT NULL,
    missing_fields_json JSON NOT NULL,
    base_history_version BIGINT UNSIGNED NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resumed_by_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resumed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, run_id),
    KEY ix_clarification_session (tenant_id, owner_user_id, session_id, status, created_at DESC),
    KEY ix_clarification_expiry (status, expires_at),
    CONSTRAINT fk_clarification_run FOREIGN KEY (tenant_id, owner_user_id, session_id, run_id)
        REFERENCES agent_run (tenant_id, owner_user_id, session_id, run_id),
    CONSTRAINT fk_clarification_resumed FOREIGN KEY (tenant_id, owner_user_id, session_id, resumed_by_run_id)
        REFERENCES agent_run (tenant_id, owner_user_id, session_id, run_id),
    CONSTRAINT ck_clarification_status CHECK (status IN ('OPEN', 'RESUMED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_clarification_resumed CHECK (status <> 'RESUMED' OR (resumed_by_run_id IS NOT NULL AND resumed_at IS NOT NULL)),
    CONSTRAINT ck_clarification_different_run CHECK (resumed_by_run_id IS NULL OR resumed_by_run_id <> run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_audit_event (
    tenant_id VARCHAR(64) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action VARCHAR(64) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    attributes_json JSON NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, event_id),
    KEY ix_audit_time (tenant_id, occurred_at DESC, event_id),
    KEY ix_audit_run (tenant_id, run_id, occurred_at),
    KEY ix_audit_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_outbox (
    tenant_id VARCHAR(64) NOT NULL,
    outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    destination VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload_json JSON NOT NULL,
    schema_version INT UNSIGNED NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner VARCHAR(128) NULL,
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, outbox_id),
    UNIQUE KEY uk_outbox_delivery (tenant_id, event_id, destination),
    KEY ix_outbox_due (status, available_at, outbox_id),
    KEY ix_outbox_lease (status, lease_until),
    KEY ix_outbox_sent (status, sent_at),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'INFLIGHT', 'SENT', 'DEAD')),
    CONSTRAINT ck_outbox_claim CHECK (status <> 'INFLIGHT' OR (lease_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_outbox_sent CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

