#!/usr/bin/env bash
set -euo pipefail
# Run as a Docker-capable user. Never modifies a pre-existing resource.
root=$(mktemp -d /tmp/ultistats-role-test.XXXXXXXX)
container="ultistats-role-test-${root##*.}"
chmod 700 "$root"
cleanup() {
    flock -w 60 -x /tmp/ultistats-browser-network.lock docker rm -f "$container" >/dev/null
    rm -rf -- "$root"
}
trap cleanup EXIT
openssl rand -hex 24 > "$root/password"
chmod 600 "$root/password"
flock -w 60 -x /tmp/ultistats-browser-network.lock docker run -d --name "$container" \
    --label ultistats.test=sql-roles --network none \
    --mount "type=bind,src=$root,dst=$root" \
    -e "POSTGRES_PASSWORD_FILE=$root/password" postgres:16-alpine >/dev/null
for attempt in $(seq 1 60); do
    if docker exec "$container" pg_isready -U postgres >/dev/null 2>&1; then break; fi
    sleep 1
done
cat > "$root/psql" <<EOF2
#!/bin/bash
exec docker exec -i -e PGPASSFILE="\${PGPASSFILE:-}" "$container" psql "\$@"
EOF2
chmod 700 "$root/psql"
export STAND_ROLE_TEST_ROOT="$root" STAND_PSQL_BIN="$root/psql"
"${NODE_BIN:-node}" --test deploy/stand/tests/sql-roles.test.mjs
