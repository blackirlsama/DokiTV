#!/bin/sh
set -eu
load_secret() {
    name=$1
    secret_file=$2
    if [ -n "$secret_file" ]; then
        if [ ! -r "$secret_file" ] || [ ! -s "$secret_file" ]; then
            printf '%s\n' "Required secret file is unavailable: $name" >&2
            exit 1
        fi
        export "$name=$(cat "$secret_file")"
    fi
}
load_secret AGENT_DB_PASSWORD "${AGENT_DB_PASSWORD_FILE:-}"
load_secret AGENT_REDIS_PASSWORD "${AGENT_REDIS_PASSWORD_FILE:-}"
load_secret DEEPSEEK_API_KEY "${DEEPSEEK_API_KEY_FILE:-}"
exec java -XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError -jar /opt/tianmu/app.jar "$@"
