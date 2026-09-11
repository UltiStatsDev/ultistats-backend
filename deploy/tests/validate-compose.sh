#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$repository_root/deploy/compose.prod.yml"

fail() {
    echo "compose validation failed: $*" >&2
    exit 1
}

[[ -f "$compose_file" ]] || fail "$compose_file does not exist"

if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    compose=(docker-compose)
else
    fail 'Docker Compose is not installed'
fi

rendered="$({
    APP_IMAGE='ghcr.io/ultistatsdev/ultistats-backend:test-sha' \
    POSTGRES_PASSWORD='test-password' \
        "${compose[@]}" -f "$compose_file" config
} 2>&1)" || fail "docker compose config failed: $rendered"

app_section="$(sed -n '/^  app:$/,/^  [a-zA-Z0-9_-]*:$/p' "$compose_file")"
postgres_section="$(sed -n '/^  postgres:$/,/^  [a-zA-Z0-9_-]*:$/p' "$compose_file")"

grep -Fq 'image: ${APP_IMAGE:?APP_IMAGE is required}' <<<"$app_section" \
    || fail 'app must use the required APP_IMAGE variable'
! grep -Eq '^[[:space:]]+build:' <<<"$app_section" \
    || fail 'app must not build source on production'
! grep -Eq '^[[:space:]]+ports:' <<<"$postgres_section" \
    || fail 'postgres must not publish a host port'
grep -Fq 'internal: true' "$compose_file" \
    || fail 'database network must be internal'
grep -Fq 'postgres_data:/var/lib/postgresql/data' "$compose_file" \
    || fail 'existing postgres_data volume must be preserved'
grep -Fq '"80:80"' "$compose_file" \
    || fail 'Caddy must expose the public HTTP API'

echo 'production compose validation passed'
