# Opt-in auth ingress

This bundle is prepared source, not permission to update a running installation. Use an independently checked immutable Backend/Web pair after combined QA/PO and the owner's separately agreed rollout. Existing human/default deployment is unchanged. CD remains disabled; the legacy workflow copies only the legacy layout and does not install this bundle automatically.

Copy `.env.example` privately to `.env` (mode0600), supply the actual full-SHA image pair and private database password, then configure origins. `PUBLIC_BIND` defaults127.0.0.1 to prevent accidental LAN exposure; selecting a public/LAN bind is an owner deployment decision. Do not reuse the sample project name with another installation's volumes. Existing DB/uploads require the verified private backup/restore procedure, not a new empty volume mistaken for migrated data.

LAN uses `Caddyfile.lan`: `PUBLIC_ORIGIN=http://<guest-host>` and `SECURE_ORIGIN=https://<staff-host>`, with matching `PUBLIC_HOST`/`STAFF_HOST`. Guests use HTTP for public Matches, photos and OBS sources; they need no CA and must never submit passwords there. Staff opens `secureOrigin + /login`, then protected Swagger/OBS admin at that same secure origin. Caddy's internal CA must be explicitly trusted on staff devices for the configured hostname. `skip_install_trust` prevents Caddy installing its CA automatically. Export only the root certificate to staff devices through an owner-approved channel, never the root private key; verify its fingerprint before trust. This source/test work does not change any trust store.

Internet uses `Caddyfile.internet`, `CADDY_CONFIG=Caddyfile.internet`, and identical public/secure HTTPS origins with the actual owner-controlled hostname. Caddy handles HTTP→HTTPS redirects and automatic certificate renewal; the owner must provide working DNS and inbound80/443 and permit ACME outbound traffic. No domain is provisioned here. Web/API/uploads share that TLS origin. A CDN or another proxy is not implicitly trusted; this bundle has no `trusted_proxies` setting, and a new proxy trust boundary needs explicit configuration and tests.

`caddy_data:/data` retains certificates, local CA and renewal state; `caddy_config:/config` retains Caddy state. Back up these privately alongside DB/uploads and preserve them across container recreation. Monitor certificate expiry and test a renewal/restart with retained state before relying on it operationally. Do not delete CA state or silently replace staff trust.

## Boundary and maintenance

Caddy uses the app's network namespace. Backend anonymous8080 and private8081 bind127.0.0.1 inside that namespace; neither is host-published. Only Caddy80/443 are published by the namespace owner. PostgreSQL and Web upstreams have no published host ports. The guest Caddy site always selects the anonymous socket; TLS staff/Internet selects private. Host/Forwarded/X-Forwarded-Proto never selects a private backend capability.

Staff proxy requests overwrite `X-UltiStats-Client-IP` with Caddy's socket `remote_host`. Backend accepts it only on the actual private connector from its loopback peer, validates one numeric IP and ignores XFF. Missing/malformed/duplicate source fails closed. Anonymous and explicit loopback entrypoints use raw socket source, ignoring all source headers. A local process with access to the private loopback socket is within the trusted owner boundary; do not expose it through an unreviewed proxy.

Validate without starting resources:

```sh
bash deploy/tests/auth-ingress_test.sh
# With an explicitly supplied Caddy binary in a temporary/private directory:
caddy adapt --adapter caddyfile --config deploy/auth/Caddyfile.lan --validate
```

Supply `.env` values in the Caddy process environment for native validation; Compose supplies them to the container. The config supports explicit native-test overrides `CADDY_HTTP_PORT`, `CADDY_HTTPS_PORT`, `ANONYMOUS_UPSTREAM`, `PRIVATE_UPSTREAM`, `WEB_UPSTREAM`; Docker defaults remain80/443,127.0.0.1:8080/8081 andweb:80. Never point a guest upstream at the private socket. The test harness uses the reserved owned ports and per-request CA file, with no global installation.

For an approved fresh synthetic/owner setup, use `docker compose --env-file .env -f compose.yaml config` first, then the reviewed startup procedure. On every app replacement Caddy must also be recreated: its `network_mode: service:app` references the app's network namespace. Use a maintenance window with both Caddy and app stopped, then recreate both against the selected immutable pair. Do not use a standalone `--no-deps app` replacement and assume an existing Caddy attached to the old namespace follows it. The legacy generic `deploy/deploy.sh` is not an auth-bundle rollout orchestrator.

During upgrade/recovery close ingress and every direct mutation path before touching writers, back up quietly, and follow [auth upgrade](../../docs/deployment/auth-upgrade.md). Failed candidate readiness never automatically restores an old unauthenticated image. Keep maintenance/no writes until a compatible auth artifact or explicit backup restoration is verified; keep the separate V10 ANY-original precision hold as well. Reopen writes only after real postflight. No `down -v`, global prune or automatic database recreation.

## Explicit native loopback development

Set `app.auth.ingress.loopback-mode=true`, `anonymous-address=127.0.0.1` (or::1), both origins to the actual loopback HTTP origin, and standard `server.port`. The main connector becomes LOOPBACK and no extra private connector is created. Backend and Web proxy must both bind only loopback; never publish that proxy through a LAN wildcard listener. This mode uses separate loopback cookies and is not a staff-LAN HTTP exception. Web103 owns the matching startup/proxy configuration and printed URLs.

## Verification status

Compose rendering, origin/source validation, connector and controller focused tests are engineering evidence. Real Caddy routing/TLS tests, full auth+CSRF tests, synthetic247→V10→V11, browser/native OBS and combined QA each retain their own exact-source execution receipt. No prepared configuration or unit test constitutes a full product acceptance result.
