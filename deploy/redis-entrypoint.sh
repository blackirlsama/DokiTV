#!/bin/sh
set -eu
umask 077
password=$(cat /run/secrets/redis-password)
case "$password" in ''|*[!a-zA-Z0-9]*) echo 'Invalid Redis secret format' >&2; exit 1 ;; esac
test "${#password}" -ge 32
config=/tmp/tianmu-redis.conf
{
    printf '%s\n' 'bind 0.0.0.0' 'port 6379' 'save ""' 'appendonly no' \
        'maxmemory 128mb' 'maxmemory-policy allkeys-lru' 'protected-mode yes'
    printf 'requirepass %s\n' "$password"
} > "$config"
unset password
chown redis:redis "$config"
exec /usr/local/bin/docker-entrypoint.sh redis-server "$config"
