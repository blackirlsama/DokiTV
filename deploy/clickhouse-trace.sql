-- v1: initialization of a dedicated Trace database. Never point this at an unrelated database.
CREATE DATABASE IF NOT EXISTS tianmu_trace;

CREATE TABLE IF NOT EXISTS tianmu_trace.trace_spans
(
    event_id UUID,
    schema_version LowCardinality(String),
    trace_id String,
    span_id String,
    parent_span_id String,
    service_name LowCardinality(String),
    span_name LowCardinality(String),
    span_type LowCardinality(String),
    status LowCardinality(String),
    start_time DateTime64(9, 'UTC'),
    duration_ns UInt64,
    run_id String,
    tenant_id LowCardinality(String),
    actor_id String,
    session_id String,
    attributes_json String CODEC(ZSTD(1)),
    envelope_json String CODEC(ZSTD(1)),
    kafka_topic LowCardinality(String),
    kafka_partition UInt64,
    kafka_offset UInt64,
    kafka_key String,
    ingested_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toDate(start_time)
ORDER BY (trace_id, span_id, event_id)
TTL toDateTime(start_time) + INTERVAL 30 DAY DELETE;

-- Background replacement is eventual. Use this view for exact event counts before merges.
CREATE VIEW IF NOT EXISTS tianmu_trace.trace_spans_current AS
SELECT * FROM tianmu_trace.trace_spans FINAL;

CREATE TABLE IF NOT EXISTS tianmu_trace.trace_rejected
(
    kafka_topic String,
    kafka_partition UInt64,
    kafka_offset UInt64,
    reason String,
    payload String CODEC(ZSTD(1)),
    received_at DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree
PARTITION BY toDate(received_at)
ORDER BY (kafka_topic, kafka_partition, kafka_offset)
TTL received_at + INTERVAL 7 DAY DELETE;

CREATE TABLE IF NOT EXISTS tianmu_trace.trace_kafka (raw String)
ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092',
         kafka_topic_list = '__TRACE_TOPIC__',
         kafka_group_name = 'tianmu-trace-clickhouse-v1',
         kafka_format = 'JSONAsString',
         kafka_num_consumers = 1,
         kafka_thread_per_consumer = 1,
         kafka_max_block_size = 1000,
         kafka_flush_interval_ms = 1000,
         kafka_handle_error_mode = 'stream';

CREATE MATERIALIZED VIEW IF NOT EXISTS tianmu_trace.trace_ingest TO tianmu_trace.trace_spans AS
WITH
    JSONExtractString(raw, 'traceId') AS trace,
    JSONExtractString(raw, 'eventId') AS event,
    JSONExtractString(raw, 'spanId') AS span,
    toInt64OrZero(JSONExtractRaw(raw, 'startTimeUnixNano')) AS start_ns
SELECT
    toUUIDOrZero(event) AS event_id,
    JSONExtractString(raw, 'schemaVersion') AS schema_version,
    trace AS trace_id,
    span AS span_id,
    JSONExtractString(raw, 'parentSpanId') AS parent_span_id,
    JSONExtractString(raw, 'serviceName') AS service_name,
    JSONExtractString(raw, 'name') AS span_name,
    JSONExtractString(raw, 'attributes', 'span.type') AS span_type,
    JSONExtractString(raw, 'status') AS status,
    fromUnixTimestamp64Nano(start_ns) AS start_time,
    JSONExtractUInt(raw, 'durationNanos') AS duration_ns,
    JSONExtractString(raw, 'attributes', 'run.id') AS run_id,
    JSONExtractString(raw, 'attributes', 'tenant.id') AS tenant_id,
    JSONExtractString(raw, 'attributes', 'actor.id') AS actor_id,
    JSONExtractString(raw, 'attributes', 'session.id') AS session_id,
    JSONExtractRaw(raw, 'attributes') AS attributes_json,
    raw AS envelope_json,
    _topic AS kafka_topic, _partition AS kafka_partition, _offset AS kafka_offset, _key AS kafka_key
FROM tianmu_trace.trace_kafka
WHERE empty(_error) AND isValidJSON(raw)
  AND JSONExtractString(raw, 'schemaVersion') IN ('1.0', '1.1')
  AND match(trace, '^[0-9a-f]{32}$') AND _key = trace
  AND match(span, '^[0-9a-f]{16}$') AND toUUIDOrZero(event) != toUUID('00000000-0000-0000-0000-000000000000')
  AND start_ns BETWEEN 946684800000000000 AND 4102444800000000000;

CREATE MATERIALIZED VIEW IF NOT EXISTS tianmu_trace.trace_reject_ingest TO tianmu_trace.trace_rejected AS
WITH
    JSONExtractString(raw, 'traceId') AS trace,
    JSONExtractString(raw, 'eventId') AS event,
    JSONExtractString(raw, 'spanId') AS span,
    toInt64OrZero(JSONExtractRaw(raw, 'startTimeUnixNano')) AS start_ns
SELECT
    _topic AS kafka_topic, _partition AS kafka_partition, _offset AS kafka_offset,
    if(notEmpty(_error), 'PARSE_ERROR', 'INVALID_ENVELOPE') AS reason,
    substring(if(notEmpty(_error), _raw_message, raw), 1, 65536) AS payload
FROM tianmu_trace.trace_kafka
WHERE NOT (empty(_error) AND isValidJSON(raw)
  AND JSONExtractString(raw, 'schemaVersion') IN ('1.0', '1.1')
  AND match(trace, '^[0-9a-f]{32}$') AND _key = trace
  AND match(span, '^[0-9a-f]{16}$') AND toUUIDOrZero(event) != toUUID('00000000-0000-0000-0000-000000000000')
  AND start_ns BETWEEN 946684800000000000 AND 4102444800000000000);
