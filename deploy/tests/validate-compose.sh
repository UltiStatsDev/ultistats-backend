#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
production_compose_file="$repository_root/deploy/compose.prod.yml"
local_compose_file="$repository_root/docker-compose.yml"

fail() {
    echo "compose validation failed: $*" >&2
    exit 1
}

[[ -f "$production_compose_file" ]] || fail "$production_compose_file does not exist"
[[ -f "$local_compose_file" ]] || fail "$local_compose_file does not exist"

if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    compose=(docker-compose)
else
    fail 'Docker Compose is not installed'
fi

production_rendered="$({
    APP_IMAGE='ghcr.io/ultistatsdev/ultistats-backend:test-sha' \
    POSTGRES_PASSWORD='test-password' \
        "${compose[@]}" -f "$production_compose_file" config
} 2>&1)" || fail "production docker compose config failed: $production_rendered"

local_rendered="$("${compose[@]}" -f "$local_compose_file" config 2>&1)" \
    || fail "local docker compose config failed: $local_rendered"

app_section="$(sed -n '/^  app:$/,/^  [a-zA-Z0-9_-]*:$/p' "$production_compose_file")"
postgres_section="$(sed -n '/^  postgres:$/,/^  [a-zA-Z0-9_-]*:$/p' "$production_compose_file")"
production_rendered_app="$(sed -n '/^  app:$/,/^  [a-zA-Z0-9_-]*:$/p' <<<"$production_rendered")"
local_rendered_app="$(sed -n '/^  ultistats:$/,/^  [a-zA-Z0-9_-]*:$/p' <<<"$local_rendered")"

grep -Fq 'image: ${APP_IMAGE:?APP_IMAGE is required}' <<<"$app_section" \
    || fail 'app must use the required APP_IMAGE variable'
! grep -Eq '^[[:space:]]+build:' <<<"$app_section" \
    || fail 'app must not build source on production'
! grep -Eq '^[[:space:]]+ports:' <<<"$postgres_section" \
    || fail 'postgres must not publish a host port'
grep -Fq 'internal: true' "$production_compose_file" \
    || fail 'database network must be internal'
grep -Fq 'postgres_data:/var/lib/postgresql/data' "$production_compose_file" \
    || fail 'existing postgres_data volume must be preserved'
grep -Fq '"80:80"' "$production_compose_file" \
    || fail 'Caddy must expose the public HTTP API'

for rendered_app in "$production_rendered_app" "$local_rendered_app"; do
    grep -Fq 'APP_STORAGE_ROOT: /app/uploads' <<<"$rendered_app" \
        || fail 'app must receive APP_STORAGE_ROOT=/app/uploads'
    grep -Fq 'source: uploads_data' <<<"$rendered_app" \
        || fail 'app must mount the uploads_data volume'
    grep -Fq 'target: /app/uploads' <<<"$rendered_app" \
        || fail 'uploads_data must be mounted at /app/uploads'
done

echo 'local and production compose validation passed'
