# Auth ingress implementation plan

> **For agentic workers:** execute inline using superpowers:executing-plans, within the assigned existing worktree. The approved standing process supplies the permanent independent reviewer. No duplicate implementation agents or repeat launch approval.

**Goal:** deliver Backend154 ingress, minimal readiness, installation metadata and a preserving upgrade/maintenance rollback for Backend150.

**Architecture:** classify transport from a configured connector's local socket, never client headers. Anonymous HTTP and private entrypoints have distinct capabilities; reverse proxies reach only private isolated upstreams. Core152 supplies identity, sessions and the shared classifier interface; enforcement153 consumes it.

**Tech Stack:** Spring Boot 3.5.7 embedded Tomcat, Kotlin/JPA/PostgreSQL/Flyway, Caddy, Docker Compose, JUnit5, Python standard-library HTTP harness.

**Spec:** approved Web merge `50bd486f3a2001b50b3e3f63d2ae4bbcf6f4c3a8`, `docs/superpowers/specs/2026-11-01-users-auth-design.md`; authoritative backend `docs/api/auth-contract.md` candidate1.0.0 at lead snapshot `c48eef30bff9e5e89e34da545b0aaad643e3c993`.

## Global constraints

- No dependent product edits until lead confirms mutually frozen wire revision AND core152 interfaces are available. This plan's proposed interfaces are sent to152/lead for acknowledgment, not represented as already implemented.
- Worktree `issue-154-auth-ingress`, branch `agent/feature/154-auth-ingress`, baseline `5c9781f2895141ca7a430588873c764d8c1e2dd6`; never touch primary32 or another index.
- Actual listener determines ANONYMOUS/SECURE/LOOPBACK; Host, Origin, Cookie, Authorization, Forwarded and X-Forwarded-* cannot upgrade it.
- Internet one HTTPS origin for Web/API/uploads. LAN guest HTTP requires no CA; staff HTTPS requires actual trusted TLS. Explicit HTTP development binds backend AND Web proxy to loopback only.
- Public installation response exactly contractVersion, installationId, entryMode, publicOrigin, secureOrigin. `/health/ready` only status UP/DOWN and HTTP200/503; no admin creation/account counts/setup secrets.
- V1–V10 immutable; V11 owned by152. Synthetic human247/V9→accepted71/V10→authV11; preserve data, photos, originals, receipts and OBS profiles. Never use human/shared fixtures.
- Known old-browser pending/uncertain means DEFER before auth enforcement/origin flip. Owner must synchronize every used legacy browser/profile; server cannot scan all IndexedDB and no post-upgrade queue wizard is claimed.
- Reserved own ports public21154/private21155/upstream21156/TLS24154/PG22154, only after checking actual listeners. Human18012/18081/shared19584 excluded. No real deployment/credentials/DNS/trust-store changes. CD stays disabled, no dispatch.
- JVM commands use `JAVA_HOME=/tmp/ultistats-backend-jdk21`, PATH JDK/bin, `GRADLE_USER_HOME=/tmp/ultistats-backend-gradle-cache`, flock `/tmp/ultistats-backend-gradle.lock`, `--no-daemon --max-workers=2`.
- Docker mutation bounded EX `/tmp/ultistats-web-heavy-check.lock` then EX `/tmp/ultistats-browser-network.lock`; browsers bounded SH network lock, actual consumer END required. Keep owned resource inventory; cleanup only own containers/networks after END, retain volumes/JAR/source/evidence, no prune/-v/foreign kills.
- Refs154/150 only; workers never merge. Engineering callback after merge/checks/available target to Web103 and Flutter-owner(PO), product stays OPEN until shared QA/PO.

## Ownership map and interfaces with152/153/Web103

| Owner | Files | Responsibility |
| --- | --- | --- |
|152 core | `build.gradle.kts`, `src/main/resources/application.yaml`, `auth/model/**`, `auth/security/AuthEntryClassifier.kt`, `auth/security/AuthRequestAccess.kt`, `auth/service/**`, `auth/controller/**`, V11, core tests | Shared types, install UUID persistence/read accessor, principal, security chain/cookies/CSRF/owner CLI.154 does not duplicate these or edit core config. |
|154 ingress | `auth/ingress/AuthIngressProperties.kt`, `AuthConnectorConfiguration.kt`, `ListenerAuthEntryClassifier.kt`, `InstallationController.kt`, `ReadinessController.kt`; `src/main/resources/application-auth-ingress.yaml` | Explicit listener binding/config validation, configured origins, implement core classifier, minimal public controllers. Package prefix `com.github.mihanizzm.ultistats`. |
|153 enforcement | access policy/method inventory, `config/WebConfig.kt`, sports/OBS/docs protection and static adapters | Public-transport rejection before processing and permission/identity checks; consumes classifier.154 tests real connector integration, does not duplicate authorization policy. |
|154 delivery | `deploy/auth/compose.yaml`, `deploy/auth/Caddyfile.lan`, `deploy/auth/Caddyfile.internet`, `deploy/auth/.env.example`, `deploy/auth/README.md`, `docs/deployment/auth-upgrade.md`, `deploy/tests/auth-ingress_test.sh`, `src/test/python/auth154_upgrade.py` | Opt-in isolated topology, TLS persistence, runbook and synthetic evidence. |
|154 readiness deployment wiring | `deploy/deploy.sh`, `deploy/tests/deploy_test.sh` | Replace docs probe and unsafe auth rollback; coordinate151 before editing shared delivery files. Existing prod compose unchanged unless lead explicitly assigns it. |
|154 tests | `src/test/kotlin/com/github/mihanizzm/ultistats/auth/ingress/{AuthIngressPropertiesTest,AuthEntrypointTest,ReadinessControllerTest}.kt`, `src/test/python/auth154_http.py` | Real ports/headers/origins/readiness/TLS evidence. |
|Web103 | Web startup/Vite/proxy/deploy and old-origin browser preflight | Loopback-only proxy, printed URLs, secure/public Web routing, actual profile queue synchronization checks. |

Consumes core exact `enum class AuthEntryMode { ANONYMOUS, SECURE, LOOPBACK }` and `interface AuthEntryClassifier { fun classify(request: HttpServletRequest): AuthEntryMode }`.152 must publish canonical package and a read-only installation UUID accessor signature before Task1 code.154 proposes `InstallationIdentityReader.installationId(): UUID`; do not implement a second persistence owner if core chooses another accessor. No auth cookie/CSRF implementation in154.

Produces classifier bean implementing the core interface; `GET /api/v1/installation` contract1.0.0 response; `GET /health/ready`200 `{"status":"UP"}` /503 `{"status":"DOWN"}`. Proposed properties prefix `app.auth.ingress`: mode, bind address/port pairs and configured publicOrigin/secureOrigin; accept only HTTP anonymous, HTTPS secure (or explicit loopback HTTP), canonical origin without credentials/path/query/fragment. Final names and constructors are pinned in this plan after152 acknowledgment, before code.

## Review focus

1. Forged forwarded/header credentials on anonymous socket must remain anonymous; test actual sockets, not just mocked servlet flags.
2. Private plaintext upstream reachable from LAN would defeat TLS even if UI uses HTTPS; isolate container networks/no published private ports, test negative reachability.
3. Private failure/readiness must not leak setup/account information or create admin; test DB unavailable and initial zero-account DB.
4. Blank HTTPS storage does not prove old HTTP queues drained; owner preflight pending/unknown must DEFER.
5. Old unauth rollback and V10 original precision loss: maintenance closes writes and disallows incompatible downgrade, not blind old-image restart.

## Task1: connector, configured metadata and readiness

- [ ] Record lead's frozen revision and152 canonical imports/accessor; integrate authorized core source into this branch without foreign edits. Complete exact constructors/property names above from actual interfaces.
- [ ] RED `AuthIngressPropertiesTest`: rejects path/query/fragment/userinfo/non-HTTPS secure origin, wildcard/non-loopback LOOPBACK bind, duplicate/conflicting ports. Explicit loopback HTTP and LAN/Internet configured origins pass.
- [ ] RED `AuthEntrypointTest`: actual sockets return ANONYMOUS on public with forged Host/XFP/Forwarded/cookie; private identity on private socket only; unknown local connector fails closed. No header-based origin reflection. Verify exact installation fields and absence account/session/admin/setup fields.
- [ ] RED `ReadinessControllerTest`: initialized migrated DB returns only UP; unavailable DB returns only DOWN503, no exception/DB/credential details. Verify no admin rows created by readiness/metadata.
- [ ] Run focused tests under JVM lock and capture expected failure; implement minimum beans/controllers/config to GREEN. Probe migration readiness by validated successful startup plus live DB query; startup fails if Flyway/validation fails.
- [ ] Run focused tests again; commit ingress slice with Refs154/150.

## Task2: real secure topology and deployment safeguards

- [ ] RED `deploy/tests/auth-ingress_test.sh`: opt-in LAN config has independent guest HTTP upstream and isolated private staff upstream, persistent Caddy `/data` and `/config`, no app/PG host publish; Internet one TLS site for Web/API/uploads; loopback profile never binds wildcard. Reject old docs health probe and blind old unauth rollback. Coordinate shared deploy files with151 first.
- [ ] Implement separate app connector addresses or isolated proxy-facing private capability using actual available core config; secure upstream is private Docker network and cannot be reached by guest proxy/host LAN. Document host native variant loopback private binding. Caddy routing never promotes anonymous connector based on headers.
- [ ] Implement trusted forwarding only from explicit private proxy socket/source; no arbitrary forwarded source for core rate policy. Preserve raw connector address for classification. Document exact trusted-source interface agreed with152; no client-provided trust flag.
- [ ] Use minimal ready URL in delivery checks. Auth upgrade failure remains maintenance/no writes until operator selects a verified compatible immutable artifact or restores private backup; never reopen unauth writes automatically.
- [ ] Run shell/static checks, then own actual TLS topology harness: guest HTTP/no CA, private login+CSRF/cookie Secure flags using core auth, untrusted certificate rejected, explicit test CA file succeeds without global installation, direct plaintext private listener not reachable outside allowed boundary. Record curl/browser scopes separately; browser/native OBS shared acceptance remains QA.
- [ ] Full JVM build, deployment tests and exacthead CI; commit delivery slice.

## Task3: preserving synthetic upgrade and rollback evidence

- [ ] Inventory original247 source/JAR and accepted71/V10 immutable source/JAR, PG tooling, disk, ports and own resources before starting any fixture. Record SHA/JAR/config hashes, PG version, schema version and nonsecret origin metadata.
- [ ] Create only own synthetic V9 DB/storage on247: teams/players/photos/matches/events/receipts/OBS profiles using actual supported V9 APIs/schema. Save fingerprints/counts and file hashes; never relabel an existing V10 fixture as V9.
- [ ] Private mode0700 backup directory, mode0600 custom pg_dump/uploads archive; restore into a second own DB/storage and verify fingerprints before proceeding. Include original metadata precision fixture at the version that supports it.
- [ ] Run accepted71/V10, verify unchanged V9 data plus valid V10 receipts/original metadata precision. Run actual authV11, verify stable installation UUID across restart/restore, all legacy data/files/profiles/receipts unchanged; no spontaneous admin. Run synthetic owner CLI via private stdin using152 instructions; never capture secret output in public evidence.
- [ ] Execute no-write maintenance rollback: preserve current DB/uploads backup and auth identity; compatible auth artifact rollback only, or restore V9/V10 copy strictly behind closed writes/private boundary. Explicitly reject downgrade across original ANY precision requirements. Compare restored data/file fingerprints; retain source/JAR/backups/evidence.
- [ ] Document operator legacy origin/profile/device inventory, owner SYNC acknowledgment, unresolved/pending→DEFER, quiet maintenance backup, exact promotion/postflight and recovery. Real rollout remains separate after QA/PO.
- [ ] Map actual results to prepared QA24 matrix without marking unexecuted cells PASS. Publish exact PR/head/checks to permanent reviewer through lead; fix findings, no selfmerge.

## Task4: engineering handoff and cleanup

- [ ] After normal checked merge, provide contract revision, merge/head/artifact hashes, target origins/readiness/TLS setup, verification evidence, remaining boundaries and `consumerMayResume` to lead for Web103/Flutter-owner(PO) callback before sharedQA.
- [ ] Keep available immutable target under explicit bounded consumer lease. Inventory own PIDs/start ticks/sockets, containers/networks/volumes and private fixture paths. No consumer END means no stop/archive.
- [ ] After END, stop/remove only exact own unneeded resources under required locks, verify baseline and send cleanup receipt. Retain volumes, source/JAR and evidence. Product154/150 stays open until QA/PO closure.

## Self-review and current gate

Scope covers backend Task154 and relevant approved joint Task5. Security session/admin behavior belongs152, exhaustive permissions153, browser startup/old-profile checksWeb103. Wire freeze and canonical152 accessor are published; actual core integration remains sequential with152 source availability. Implementation and scoped runtime checks are recorded in docs/qa/auth154/README.md; the prepared shared QA matrix remains NOT_RUN here. The atomic152 integrator owns final combined full build/CI/review and available target.154 supplies its coherent source/evidence and cleanup receipt; it does not open a competing PR.

## Coordination acknowledgment

151 callback confirms PR155 head `14e4f33e7c6d58a8715d6ef9aa936993885b43a2` does not edit `deploy/deploy.sh` or `deploy/tests/deploy_test.sh`;154 owns those changes. Preserve the full lowercase40SHA image/revision interface.151 review/CI/merge and frozen wire/core availability are not implied by this file-ownership acknowledgment.

## Concrete connector/config decisions (frozen wire, core interfaces available)

- `auth.ingress.AuthOriginConfiguration` SPI committed926b3eb; properties `AuthIngressProperties(publicOrigin:String="http://localhost:8080", secureOrigin:String="https://localhost:8443", loopbackMode:Boolean=false, anonymousAddress:String="127.0.0.1", privatePort:Int=8081)` implements it. Prefix `app.auth.ingress`. Primary port stays standard `server.port`; privatePort0 allows isolated test allocation. Numeric bind choices only, no DNS resolution. Configured origins permit HTTP(S) authority only, no path/query/fragment/userinfo; private HTTPS mandatory except explicit loopback HTTP.
- `ListenerAuthEntryClassifier(properties:AuthIngressProperties)` stores actual Tomcat connector references registered by `AuthConnectorConfiguration`. It compares servlet localPort/localAddr with actual bound connector, never Host/XFP/Origin/credentials. Unrecognized socket returns ANONYMOUS. LOOPBACK main connector is loopback-only; otherwise main is ANONYMOUS and private additional connector binds127.0.0.1. No forwarded header processing/RemoteIpValve allowed. Private connector reports secure scheme to framework but capability comes from connector identity.
- `AuthConnectorConfiguration` enables properties, supplies classifier and Tomcat customizer after standard server properties. Checks conflicting primary/private fixed ports, enforces bind, disables server-header forwarding, and rejects unsafe forwarding config.152/153 consume shared types/SPI; no duplicate security chain.
- Docker opt-in Caddy uses `network_mode: service:app`; private loopback remains within that namespace. App publishes only Caddy80/443, not backend primary/private/PG. Caddy public listener always proxies anonymous socket; staff TLS proxies private socket. Backend loopback mode has no exposed network staff option and must pair with loopback-only Web proxy.
- Readiness implementation will use DataSource live query with bounded timeout, plus successful Flyway/validation startup (fail startup on invalid migrations); metadata controller consumes actual core AuthService only after its implementation is available.
- Lead newer callback requires151merge/rebase before committing deploy.sh/deploy_test.sh changes. Existing local RED→GREEN changes remain uncommitted until that gate; config/test work continues.

### Verified rate-limit source interface

Core152 requests `auth.ingress.AuthClientSource { fun source(request:HttpServletRequest):String }`, committed separately a36c2c3.154 implements `ListenerAuthClientSource(classifier:AuthEntryClassifier)` as a bean. ANONYMOUS/LOOPBACK use raw socket peer, ignore all source headers. SECURE requires actual private connector plus loopback peer and one dedicated `X-UltiStats-Client-IP` numeric IP; missing/malformed/duplicate values fail closed403 AUTH_PERMISSION_DENIED. Caddy always overwrites it from `{remote_host}`, never forwards a client-supplied value/XFF. IP normalization permits numeric IPv4/IPv6 only, no DNS/zone/comma/port. No trusted_proxies are enabled; external reverse proxies need a separately reviewed source trust configuration. This prevents staff clients sharing one localhost rate bucket and prevents forged source bypass.

## Execution closeout

Owned engineering slice, actual247/V9→V10→V11 preserving upgrade, populated-auth backup/restore, no-write old recovery and HTTPS boundary checks are recorded in `docs/qa/auth154/README.md`. All154selftests reached consumerEND, no external154runtimelease existed, exact owned resources cleaned with private receipt. Source/model/schema final alignment and atomicPR remain152 responsibilities; product154/150 remainOPEN pending combinedQA/PO. Later nullableJSON/OpenAPI correction is not covered by the older87ca154checkpoint as full-wire acceptance.

