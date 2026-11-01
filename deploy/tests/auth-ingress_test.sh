#!/usr/bin/env bash
set -Eeuo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
export APP_IMAGE='ghcr.io/ultistatsdev/ultistats-backend:0123456789abcdef0123456789abcdef01234567'
export WEB_IMAGE='ultistats-web:synthetic-immutable'
export POSTGRES_PASSWORD='synthetic-compose-validation'
export PUBLIC_ORIGIN='http://192.0.2.1'
export SECURE_ORIGIN='https://staff.example.test'
export PUBLIC_HOST='192.0.2.1'
export STAFF_HOST='staff.example.test'
export PUBLIC_BIND='127.0.0.1'
export HTTP_PORT=21154 TLS_PORT=24154

docker compose -f "$repo_root/deploy/auth/compose.yaml" config --format json > "$rendered"
python3 - "$rendered" <<'PY'
import json,sys
c=json.load(open(sys.argv[1]));s=c['services'];app=s['app'];proxy=s['caddy']
assert proxy['network_mode']=='service:app', 'private loopback namespace must be shared by Caddy only'
assert not proxy.get('ports'), 'only namespace owner may publish ingress ports'
assert {(p['target'],p['published'],p['host_ip']) for p in app['ports']} == {(80,'21154','127.0.0.1'),(443,'24154','127.0.0.1')}, 'backend/private ports must never be host-published'
assert not s['postgres'].get('ports') and not s['web'].get('ports'), 'DB/Web upstreams must stay private'
env=app['environment']
assert env['APP_AUTH_INGRESS_ANONYMOUS_ADDRESS']=='127.0.0.1'
assert env['APP_AUTH_INGRESS_LOOPBACK_MODE']=='false'
assert env['SERVER_FORWARD_HEADERS_STRATEGY']=='none'
assert env['APP_AUTH_INGRESS_PRIVATE_PORT']=='8081'
assert env['APP_AUTH_INGRESS_PUBLIC_ORIGIN']=='http://192.0.2.1'
assert env['APP_AUTH_INGRESS_SECURE_ORIGIN']=='https://staff.example.test'
assert c['networks']['database']['internal'] is True
assert {v['target'] for v in proxy['volumes']} >= {'/data','/config','/etc/caddy/Caddyfile'}
assert {v['target'] for v in app['volumes']} >= {'/app/uploads'}
assert {v['target'] for v in s['postgres']['volumes']} >= {'/var/lib/postgresql/data'}
print('isolated auth compose rendering passed')
PY
