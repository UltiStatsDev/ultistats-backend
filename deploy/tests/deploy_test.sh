#!/usr/bin/env bash

set -Eeuo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
deploy_script="$repository_root/deploy/deploy.sh"
image='ghcr.io/ultistatsdev/ultistats-backend:0123456789abcdef0123456789abcdef01234567'
revision='0123456789abcdef0123456789abcdef01234567'

fail() {
    echo "deploy test failed: $*" >&2
    exit 1
}

assert_contains() {
    local file="$1"
    local expected="$2"
    grep -Fq -- "$expected" "$file" || fail "$file does not contain: $expected"
}

make_fixture() {
    local scenario="$1"
    fixture="$(mktemp -d)"
    mkdir -p "$fixture/bin" "$fixture/root/backups"
    cp "$repository_root/deploy/compose.prod.yml" "$fixture/root/compose.prod.yml"
    printf 'POSTGRES_PASSWORD=test-password\n' > "$fixture/root/.env"
    printf 'legacy-image:old\n' > "$fixture/state"
    touch \
        "$fixture/root/backups/pre-20260101T000000Z-old.dump" \
        "$fixture/root/backups/pre-20260201T000000Z-old.dump" \
        "$fixture/root/backups/pre-20260301T000000Z-old.dump"

    cat > "$fixture/bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s APP_IMAGE=%s\n' "$*" "${APP_IMAGE:-}" >> "$FAKE_LOG"

if [[ "$*" == *'--filter label=com.docker.compose.service=app'* ]]; then
    printf 'fake-app-container\n'
elif [[ "$*" == *'exec -T postgres pg_dump'* ]]; then
    [[ "$SCENARIO" == 'empty-backup' ]] || printf 'valid-custom-dump'
elif [[ "$1" == 'inspect' ]]; then
    cat "$FAKE_STATE"
elif [[ "$*" == *'up -d --no-deps app'* ]]; then
    printf '%s\n' "${APP_IMAGE:?}" > "$FAKE_STATE"
fi
FAKE_DOCKER

    cat > "$fixture/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$FAKE_CURL_LOG"
current_image="$(cat "$FAKE_STATE")"
if [[ "$SCENARIO" == 'health-failure' && "$current_image" == ghcr.io/* ]]; then
    exit 22
fi
printf '{"openapi":"3.1.0"}'
FAKE_CURL

    cat > "$fixture/bin/flock" <<'FAKE_FLOCK'
#!/usr/bin/env bash
exit 0
FAKE_FLOCK

    chmod +x "$fixture/bin/docker" "$fixture/bin/curl" "$fixture/bin/flock"
    export PATH="$fixture/bin:$PATH"
    export DEPLOY_ROOT="$fixture/root"
    export DEPLOY_HEALTH_ATTEMPTS=2
    export DEPLOY_HEALTH_INTERVAL_SECONDS=0
    export DEPLOY_MIN_FREE_KB=1
    export DEPLOY_BACKUP_RETENTION=2
    export FAKE_LOG="$fixture/docker.log"
    export FAKE_CURL_LOG="$fixture/curl.log"
    export FAKE_STATE="$fixture/state"
    export SCENARIO="$scenario"
}

run_success_test() {
    make_fixture success
    "$deploy_script" "$image" "$revision"

    [[ -n "$(find "$fixture/root/backups" -type f -name '*.dump' -size +0 -print -quit)" ]] \
        || fail 'success path did not create a non-empty backup'
    assert_contains "$fixture/root/.env" "APP_IMAGE=$image"
    assert_contains "$fixture/root/DEPLOYED_REVISION" "$revision"
    assert_contains "$fixture/docker.log" "pull app APP_IMAGE=$image"
    assert_contains "$fixture/docker.log" "up -d --no-deps app APP_IMAGE=$image"
    [[ "$(find "$fixture/root/backups" -type f -name 'pre-*.dump' | wc -l | tr -d ' ')" == 2 ]] \
        || fail 'backup retention did not keep exactly two dumps'
    rm -rf "$fixture"
}

run_empty_backup_test() {
    make_fixture empty-backup
    if "$deploy_script" "$image" "$revision"; then
        fail 'deployment succeeded with an empty database backup'
    fi

    ! grep -Fq 'pull app' "$fixture/docker.log" \
        || fail 'deployment pulled an image after backup failure'
    ! grep -Fq 'APP_IMAGE=' "$fixture/root/.env" \
        || fail 'backup failure changed the environment file'
    rm -rf "$fixture"
}

run_rollback_test() {
    make_fixture health-failure
    if "$deploy_script" "$image" "$revision"; then
        fail 'deployment succeeded despite an unhealthy new image'
    fi

    [[ "$(cat "$fixture/state")" == 'legacy-image:old' ]] \
        || fail 'failed deployment did not restore the old image'
    assert_contains "$fixture/docker.log" 'up -d --no-deps app APP_IMAGE=legacy-image:old'
    ! grep -Fq 'APP_IMAGE=' "$fixture/root/.env" \
        || fail 'rollback changed the environment file'
    [[ ! -e "$fixture/root/DEPLOYED_REVISION" ]] \
        || fail 'failed deployment recorded a successful revision'
    rm -rf "$fixture"
}

[[ -x "$deploy_script" ]] || fail "$deploy_script is not executable"
run_success_test
run_empty_backup_test
run_rollback_test
echo 'deploy script tests passed'
