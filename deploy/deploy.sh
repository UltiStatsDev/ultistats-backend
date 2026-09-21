#!/usr/bin/env bash

set -Eeuo pipefail

readonly target_image="${1:-}"
readonly target_revision="${2:-}"
readonly deploy_root="${DEPLOY_ROOT:-/opt/ultistats}"
readonly compose_file="$deploy_root/compose.prod.yml"
readonly env_file="$deploy_root/.env"
readonly backup_directory="$deploy_root/backups"
readonly revision_file="$deploy_root/DEPLOYED_REVISION"
readonly health_url="${DEPLOY_HEALTH_URL:-http://127.0.0.1/v3/api-docs}"
readonly health_attempts="${DEPLOY_HEALTH_ATTEMPTS:-30}"
readonly health_interval_seconds="${DEPLOY_HEALTH_INTERVAL_SECONDS:-2}"
readonly minimum_free_kb="${DEPLOY_MIN_FREE_KB:-1048576}"
readonly backup_retention="${DEPLOY_BACKUP_RETENTION:-10}"
readonly lock_file="$deploy_root/.deploy.lock"

log() {
    printf '[deploy] %s\n' "$*"
}

fail() {
    printf '[deploy] ERROR: %s\n' "$*" >&2
    exit 1
}

[[ "$target_revision" =~ ^[0-9a-f]{40}$ ]] \
    || fail 'revision must be a full lowercase Git commit SHA'
[[ "$target_image" == "ghcr.io/ultistatsdev/ultistats-backend:$target_revision" ]] \
    || fail 'image must be the immutable GHCR tag for the supplied revision'
[[ "$health_attempts" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DEPLOY_HEALTH_ATTEMPTS must be a positive integer'
[[ "$health_interval_seconds" =~ ^[0-9]+$ ]] \
    || fail 'DEPLOY_HEALTH_INTERVAL_SECONDS must be a non-negative integer'
[[ "$minimum_free_kb" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DEPLOY_MIN_FREE_KB must be a positive integer'
[[ "$backup_retention" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DEPLOY_BACKUP_RETENTION must be a positive integer'
[[ -f "$compose_file" ]] || fail "$compose_file does not exist"
[[ -f "$env_file" ]] || fail "$env_file does not exist"

exec 9> "$lock_file"
flock --nonblock 9 || fail 'another deployment is already running'

compose() {
    docker compose --env-file "$env_file" -f "$compose_file" "$@"
}

wait_until_healthy() {
    local attempt
    for ((attempt = 1; attempt <= health_attempts; attempt++)); do
        if curl --fail --silent --show-error "$health_url" >/dev/null; then
            return 0
        fi
        sleep "$health_interval_seconds"
    done
    return 1
}

current_app_container() {
    docker ps \
        --filter label=com.docker.compose.project=ultistats \
        --filter label=com.docker.compose.service=app \
        --format '{{.ID}}' | head -n 1
}

legacy_uploads_state() {
    local container_id="$1"

    docker exec "$container_id" sh -c \
        'set -eu
        if [ ! -d /app/uploads ]; then
            printf "missing\n"
            exit 0
        fi
        first_entry="$(find /app/uploads -mindepth 1 -maxdepth 1 -print -quit)"
        if [ -n "$first_entry" ]; then
            printf "nonempty\n"
        else
            printf "empty\n"
        fi'
}

write_image_to_env() {
    local image="$1"
    local temporary_env="$env_file.tmp.$$"

    awk -v image="$image" '
        BEGIN { replaced = 0 }
        /^APP_IMAGE=/ { print "APP_IMAGE=" image; replaced = 1; next }
        { print }
        END { if (!replaced) print "APP_IMAGE=" image }
    ' "$env_file" > "$temporary_env"
    chmod 600 "$temporary_env"
    mv "$temporary_env" "$env_file"
}

prune_old_backups() {
    local backup_files
    local remove_count
    local index

    shopt -s nullglob
    backup_files=("$backup_directory"/pre-*.dump)
    shopt -u nullglob
    remove_count=$((${#backup_files[@]} - backup_retention))
    if ((remove_count <= 0)); then
        return
    fi

    for ((index = 0; index < remove_count; index++)); do
        rm -- "${backup_files[$index]}"
        rm -f -- "${backup_files[$index]%.dump}-uploads.tar"
    done
}

bootstrap_uploads_volume() {
    local uploads_backup_file="$1"
    local restore_status

    [[ -s "$uploads_backup_file" ]] || return 0

    log "bootstrapping persistent uploads storage from $uploads_backup_file"
    if APP_IMAGE="$target_image" compose run --rm --no-deps -T \
        --user root \
        --entrypoint sh \
        app -c '
            set -eu
            first_entry="$(find /app/uploads -mindepth 1 -maxdepth 1 -print -quit)"
            if [ -n "$first_entry" ]; then
                exit 42
            fi
            cleanup() {
                find /app/uploads -mindepth 1 -delete || true
            }
            if ! tar -xf - -C /app/uploads; then
                cleanup
                exit 1
            fi
            if ! chown -R appuser:appgroup /app/uploads; then
                cleanup
                exit 1
            fi
        ' < "$uploads_backup_file"; then
        return 0
    else
        restore_status=$?
    fi

    if ((restore_status == 42)); then
        log 'persistent uploads storage is not empty; keeping its existing files'
        return 0
    fi

    return "$restore_status"
}

rollback() {
    local reason="$1"

    log "$reason; rolling back to $previous_image"
    APP_IMAGE="$previous_image" compose up -d --no-deps app
    wait_until_healthy || fail 'new image failed and rollback is unhealthy'
    fail "$reason; previous image restored"
}

mkdir -p "$backup_directory"
chmod 700 "$backup_directory"
available_kb="$(df -Pk "$backup_directory" | awk 'NR == 2 { print $4 }')"
[[ "$available_kb" =~ ^[0-9]+$ ]] || fail 'could not determine available disk space'
((available_kb >= minimum_free_kb)) \
    || fail "less than ${minimum_free_kb} KiB is available for a database backup"
backup_file="$backup_directory/pre-$(date -u +%Y%m%dT%H%M%SZ)-${target_revision}.dump"
uploads_backup_file="${backup_file%.dump}-uploads.tar"
uploads_state=''

app_container_id="$(current_app_container)"
[[ -n "$app_container_id" ]] || fail 'current application container was not found'
previous_image="$(docker inspect --format '{{.Config.Image}}' "$app_container_id")"
[[ -n "$previous_image" ]] || fail 'current application image could not be determined'

log "creating PostgreSQL backup at $backup_file"
APP_IMAGE="$previous_image" compose exec -T postgres pg_dump \
    --username=ultistats \
    --dbname=ultistats \
    --format=custom > "$backup_file"
chmod 600 "$backup_file"
[[ -s "$backup_file" ]] || fail 'database backup is empty; refusing to deploy'

if ! uploads_state="$(legacy_uploads_state "$app_container_id")"; then
    fail 'could not inspect current uploads storage; refusing to deploy'
fi
case "$uploads_state" in
    nonempty)
        log "creating uploads backup at $uploads_backup_file"
        docker cp "$app_container_id:/app/uploads/." - > "$uploads_backup_file"
        chmod 600 "$uploads_backup_file"
        [[ -s "$uploads_backup_file" ]] || fail 'uploads backup is empty; refusing to deploy'
        ;;
    empty | missing)
        log "current uploads storage is $uploads_state; no uploads backup is needed"
        ;;
    *)
        fail "unexpected current uploads state: $uploads_state"
        ;;
esac
prune_old_backups

log "pulling $target_image"
APP_IMAGE="$target_image" compose pull app
bootstrap_uploads_volume "$uploads_backup_file" \
    || fail 'uploads restoration failed; current application container was kept'
APP_IMAGE="$target_image" compose up -d --no-deps app

if ! wait_until_healthy; then
    rollback 'new image failed its health check'
fi

write_image_to_env "$target_image"
temporary_revision="$revision_file.tmp.$$"
printf '%s\n' "$target_revision" > "$temporary_revision"
mv "$temporary_revision" "$revision_file"

log "deployment of $target_revision completed"
