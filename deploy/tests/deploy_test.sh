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
    mkdir -p \
        "$fixture/bin" \
        "$fixture/legacy-uploads" \
        "$fixture/persistent-uploads" \
        "$fixture/root/backups"
    cp "$repository_root/deploy/compose.prod.yml" "$fixture/root/compose.prod.yml"
    printf 'POSTGRES_PASSWORD=test-password\n' > "$fixture/root/.env"
    printf 'legacy-image:old\n' > "$fixture/state"
    printf 'legacy-photo\n' > "$fixture/legacy-uploads/legacy-photo.jpg"
    if [[ "$scenario" == 'existing-volume' ]]; then
        printf 'persistent-photo\n' > "$fixture/persistent-uploads/persistent-photo.jpg"
    fi
    touch \
        "$fixture/root/backups/pre-20260101T000000Z-old.dump" \
        "$fixture/root/backups/pre-20260201T000000Z-old.dump" \
        "$fixture/root/backups/pre-20260301T000000Z-old.dump"

    cat > "$fixture/bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s APP_IMAGE=%s\n' "$*" "${APP_IMAGE:-}" >> "$FAKE_LOG"

if [[ "$*" == *'--filter label=com.docker.compose.service=app'* ]]; then
    if [[ "$(cat "$FAKE_STATE")" == ghcr.io/* ]]; then
        printf 'fake-new-app-container\n'
    else
        printf 'fake-old-app-container\n'
    fi
elif [[ "$*" == *'exec -T postgres pg_dump'* ]]; then
    [[ "$SCENARIO" == 'empty-backup' ]] || printf 'valid-custom-dump'
elif [[ "$1" == 'inspect' ]]; then
    cat "$FAKE_STATE"
elif [[ "$*" == *'up -d --no-deps app'* ]]; then
    printf '%s\n' "${APP_IMAGE:?}" > "$FAKE_STATE"
elif [[ "$1" == 'exec' && "$2" == 'fake-old-app-container' ]]; then
    [[ "$SCENARIO" == 'probe-failure' ]] && exit 125
    printf 'nonempty\n'
elif [[ "$1" == 'exec' && "$2" == 'fake-new-app-container' ]]; then
    find "$FAKE_PERSISTENT_UPLOADS" -mindepth 1 -maxdepth 1 -print -quit | grep -q .
elif [[ "$1" == 'exec' && "$2" == '--user' ]]; then
    exit 0
elif [[ "$1" == 'cp' && "$2" == 'fake-old-app-container:/app/uploads/.' && "$3" == '-' ]]; then
    tar -C "$FAKE_LEGACY_UPLOADS" -cf - .
elif [[ "$1" == 'cp' && "$2" == '-' && "$3" == 'fake-new-app-container:/app/uploads' ]]; then
    [[ "$SCENARIO" == 'restore-failure' ]] && exit 1
    tar -C "$FAKE_PERSISTENT_UPLOADS" -xf -
elif [[ "$*" == *'run --rm --no-deps'* ]]; then
    if find "$FAKE_PERSISTENT_UPLOADS" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
        exit 42
    fi
    if [[ "$SCENARIO" == 'restore-failure' ]]; then
        cat >/dev/null
        printf 'partial-photo\n' > "$FAKE_PERSISTENT_UPLOADS/partial-photo.jpg"
        if [[ "$*" == *'find /app/uploads -mindepth 1 -delete'* ]]; then
            rm "$FAKE_PERSISTENT_UPLOADS/partial-photo.jpg"
        fi
        exit 1
    fi
    tar -C "$FAKE_PERSISTENT_UPLOADS" -xf -
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
    export FAKE_LEGACY_UPLOADS="$fixture/legacy-uploads"
    export FAKE_PERSISTENT_UPLOADS="$fixture/persistent-uploads"
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
    assert_contains "$fixture/docker.log" 'run --rm --no-deps'
    assert_contains "$fixture/docker.log" "up -d --no-deps app APP_IMAGE=$image"
    assert_contains "$fixture/docker.log" 'cp fake-old-app-container:/app/uploads/. -'
    ! grep -Fq 'cp - fake-new-app-container:/app/uploads' "$fixture/docker.log" \
        || fail 'deployment restored files only after replacing the old container'
    assert_contains "$fixture/persistent-uploads/legacy-photo.jpg" 'legacy-photo'
    [[ -n "$(find "$fixture/root/backups" -type f -name 'pre-*-uploads.tar' -size +0 -print -quit)" ]] \
        || fail 'success path did not create a non-empty uploads backup'
    [[ "$(find "$fixture/root/backups" -type f -name 'pre-*.dump' | wc -l | tr -d ' ')" == 2 ]] \
        || fail 'backup retention did not keep exactly two dumps'
    rm -rf "$fixture"
}

run_existing_volume_test() {
    make_fixture existing-volume
    "$deploy_script" "$image" "$revision"

    assert_contains "$fixture/persistent-uploads/persistent-photo.jpg" 'persistent-photo'
    [[ ! -e "$fixture/persistent-uploads/legacy-photo.jpg" ]] \
        || fail 'deployment overwrote a non-empty persistent uploads volume'
    assert_contains "$fixture/docker.log" 'run --rm --no-deps'
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

run_restore_failure_test() {
    make_fixture restore-failure
    if "$deploy_script" "$image" "$revision"; then
        fail 'deployment succeeded despite an uploads restoration failure'
    fi

    [[ "$(cat "$fixture/state")" == 'legacy-image:old' ]] \
        || fail 'uploads restoration failure did not restore the old image'
    ! grep -Fq 'up -d --no-deps app APP_IMAGE=' "$fixture/docker.log" \
        || fail 'uploads restoration failure replaced the old application container'
    assert_contains "$fixture/legacy-uploads/legacy-photo.jpg" 'legacy-photo'
    [[ -z "$(find "$fixture/persistent-uploads" -mindepth 1 -print -quit)" ]] \
        || fail 'uploads restoration failure left a partial persistent volume'
    ! grep -Fq 'APP_IMAGE=' "$fixture/root/.env" \
        || fail 'uploads restoration failure changed the environment file'
    [[ ! -e "$fixture/root/DEPLOYED_REVISION" ]] \
        || fail 'uploads restoration failure recorded a successful revision'
    rm -rf "$fixture"
}

run_probe_failure_test() {
    make_fixture probe-failure
    if "$deploy_script" "$image" "$revision"; then
        fail 'deployment succeeded despite a legacy uploads probe failure'
    fi

    [[ "$(cat "$fixture/state")" == 'legacy-image:old' ]] \
        || fail 'legacy uploads probe failure replaced the old image'
    assert_contains "$fixture/legacy-uploads/legacy-photo.jpg" 'legacy-photo'
    ! grep -Fq 'pull app' "$fixture/docker.log" \
        || fail 'legacy uploads probe failure pulled the new image'
    ! grep -Fq 'up -d --no-deps app' "$fixture/docker.log" \
        || fail 'legacy uploads probe failure replaced the old application container'
    rm -rf "$fixture"
}

run_rollback_test() {
    make_fixture health-failure
    if "$deploy_script" "$image" "$revision"; then
        fail 'deployment succeeded despite an unhealthy new image'
    fi

    [[ "$(cat "$fixture/state")" == 'legacy-image:old' ]] \
        || fail 'failed deployment did not restore the old image'
    assert_contains "$fixture/persistent-uploads/legacy-photo.jpg" 'legacy-photo'
    assert_contains "$fixture/docker.log" 'up -d --no-deps app APP_IMAGE=legacy-image:old'
    ! grep -Fq 'APP_IMAGE=' "$fixture/root/.env" \
        || fail 'rollback changed the environment file'
    [[ ! -e "$fixture/root/DEPLOYED_REVISION" ]] \
        || fail 'failed deployment recorded a successful revision'
    rm -rf "$fixture"
}

[[ -x "$deploy_script" ]] || fail "$deploy_script is not executable"
run_success_test
run_existing_volume_test
run_empty_backup_test
run_restore_failure_test
run_probe_failure_test
run_rollback_test
echo 'deploy script tests passed'
