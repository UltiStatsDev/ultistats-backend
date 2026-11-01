# New stand SQL cluster bootstrap plan

> Execute inline using superpowers:executing-plans and test-driven-development; permanent reviewer checks the exact whole branch. Existing approved execution needs no new product/start interview.

**Goal:** provide the missing initialization boundary for a NEW dedicated stand PostgreSQL cluster, before the existing restricted-login provider can run.

**Scope:** Backend #169/#168, baseline application247 only; PR base candidate/private-stand-v1 at merged16b7095. No application/DTO/migration edits, host installation, existing cluster adoption, runtime target reuse, publication or self-merge.

**Files:** `deploy/stand/sql-bootstrap.mjs`, `bootstrap-contract-v1.json`, `sql-bootstrap.md`, bootstrap source/real-PG tests and runner. Existing roles module/policy remain reviewed immutable inputs.

## Interface and sequence

Freeze `prepareSqlCluster(options)` and `finalizeSqlCluster(options)` with installer125/provider170 before their coupled calls. Canonical logical key is `ultistats_private`; runtime12hex namespace is a separate explicitly bound field, never an API UUID. Private descriptor binds exact container/image/volume/network creation identity, private creation receipt, admin/migration/service/login credential paths and bootstrap receipt path. Database and distinct migration/service/login role names are fixed from the logical key. Docker inspection and PostgreSQL metadata must both match before writes.

Prepare validates new owned resource receipt/labels/no PG host publication and empty cluster; it creates distinct migration owner/service DML roles, database owner/comment, maintenance/template PUBLIC restrictions and pre-migration service defaults. Actual247 Spring Boot uses explicit SPRING_FLYWAY_USER/PASSWORD separate from DATABASE_USERNAME/PASSWORD. Finalize verifies actual V1–V9 Flyway history installed by migration owner, service DML and exact ownership, then invokes existing169 frontend policy through a private owned-container psql adapter. HBA source and live effective receipt are frozen with170/125; only exact owned proxy/application/migration source addresses are accepted, no wildcard ingress.

Private durable journal records exact identity/phases; concurrent calls are excluded. A complete matching receipt is idempotent. Unknown markers/collisions, partial ambiguity, changed identities or foreign resources reject without adoption or destructive recovery. Ingress stays closed through failure and until separate170 readiness.

## Review focus

- Spoofed logical/runtime namespace, Docker labels, image, volume/network or cluster identifier: refuse before mutation.
- Existing cluster/role/DB collisions and interrupted phase: never overwrite/recreate/adopt to force success.
- Actual Flyway separate-owner behavior: verify with immutable actual247 artifact and real PG, not assumed properties or manual-only SQL.
- Service DML/defaults must work; frontend stays explicit policy and cannot inherit future security data or DDL/admin rights.
- Credential paths/symlinks/modes, subprocess errors and durable receipts must not expose passwords/verifiers or accept request-controlled executables.

## Task 1: contract and bootstrap boundary

- [x] Freeze exact producer/consumer input, ownership labels, HBA and phase receipt fields.
- [x] Write validation/new-cluster/collision/idempotence tests, observe RED.
- [x] Implement private Docker/psql transport, new-cluster guard, phase journal and prepare/finalize.
- [x] Run real owned PostgreSQL GREEN for permissions, collisions, HBA and recovery boundaries.

## Task 2: actual247 and review handoff

- [x] Obtain immutable247 source artifact only from its owner; use own new isolated runtime/DB namespace.
- [x] Run actual247 migrations as migrationRole and service pool as serviceRole; verify actual read/write and frontend restricted login, plus negative controls.
- [ ] Verify meaningful source suites/current CI and required backend checks, publish own PR with Refs #169/#168 and independent exact-head review.
- [ ] Cleanup exact owned completed runtime resources after consumer END; retain immutable evidence and source handoff. Product issues stay OPEN pending shared QA/PO/actual delivery.

## Execution evidence

Initial real NEW-PG run failed because provider was missing; source/permission runner then passed. HBA-ready replay and privileged service-role replay were each reproduced RED and fixed. Final focused integration currently10/10 without skips, with both independently supplied247 artifact and default raw247 source build path. Raw247 build performed once per coherent runner; no auth/current-control substitution. Root authorized necessary bootstrap-only candidate CI job/config regression in this PR; original roles/fence/CD/promotion remain unchanged. Installer/provider interface ACK remains tracked separately from source execution; no host install or actual delivery claim.

Final identity regression: privileged frontend replay was observed RED, then exact frontend role OID/marker/nonadmin/membership/ownership verification made the final real suite10/10 GREEN. Installer125 field/label/phase ACK read directly from its owned versioned contract, provider170 transport and field ACK received; actual coupled installation remains gated on reviewed immutable source.

Independent PR175 P1 at cc64d77: completed replay returned stale role proof after ACL grants changed. Original report/probes retained. Own real-PG RED reproduced; after existing169 canonical reconciliation bootstrap now captures a private ACL-contribution digest and compares fresh read-only metadata on every later prepare/bind/finalize. No grant-policy duplication or permission mutations on replay. Direct/PUBLIC/column/catalog/function/schema/DB/default drift blocked, clean restoration/repeat passes; final focused actual suite11/11 without skips.
