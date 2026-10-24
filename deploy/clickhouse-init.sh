#!/bin/sh
set -eu
case "$KAFKA_TRACE_TOPIC" in
    ''|*[!A-Za-z0-9._-]*) echo 'Invalid Kafka trace topic' >&2; exit 1 ;;
esac
sed "s/__TRACE_TOPIC__/$KAFKA_TRACE_TOPIC/g" /opt/tianmu/trace-schema.sql |
    clickhouse-client --host 127.0.0.1 --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --multiquery
