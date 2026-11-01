# Stand SQL roles implementation plan

> Execute inline using superpowers:executing-plans; independent permanent reviewer checks the complete branch.

**Goal:** give the optional stand a database-only DML login with explicit, reproducible permissions.

**Architecture:** Node module invokes PostgreSQL's psql with private credential files. One transaction validates exact installation/cluster/database identity, serializes reconciliation, rejects unsafe ownership/membership, and applies a bundled versioned policy. The SQL maintenance provider owns transaction drain and network fencing.

**Tech stack:** Node 22, psql, PostgreSQL 16, node:test.

**Spec:** approved private computer stand design, Web PR126; Backend issues #168/#169 and Web #121.

## Constraints

Own installation only; no host installation, human authentication changes, session termination, deployment, self-merge, or foreign database ACL changes. Migration/service identities remain separate from the frontend login. No wildcard future table grants. Secrets stay in owner-only files; errors/proofs contain no credentials. Source readiness does not mean installed delivery. First app baseline remains finalized Web39/API247 plus reviewed tooling.

## Interface

`provisionSqlRole(options)`, `reconcileSqlRole(options)`, `revokeSqlRole(options)` return promises of sanitized v1 proof. Options: `identity: {schemaVersion:1, installationId, database, databaseOid, systemIdentifier, migrationRole, serviceRole, loginRole}`, `adminCredentialsPath`, `loginCredentialsPath` (not needed to revoke), optional trusted `psqlPath`. Private admin JSON: `{host,port,database,user,password,sslmode}`; private login JSON: `{password}`. Proof includes identity, `rolePolicySha256`, `state`.

The installer provides a dedicated cluster, exact database owner/OID/system identifier and database comment `ultistats-stand:<installationId>`. It removes PUBLIC CONNECT/TEMP on other databases; this module checks rather than changing them. Only the public application schema is supported initially. Network/HBA and proxy identity are provider/installer responsibilities.

## Review focus

- Existing role ownership/membership/foreign access: fail closed, never adopt a foreign login.
- PUBLIC grants, column grants and default privileges: cannot widen this login after reconciliation.
- New auth/session/unknown objects: no implicit grants; unknown schemas fail closed.
- Wrong cluster/OID/comment or insecure secret file: reject before changes.
- Existing idle connection after revoke: DML denied; active transaction boundary belongs to maintenance provider.

## Task 1: policy and reconciliation

Files: `deploy/stand/sql-roles.mjs`, `sql-role-policy-v1.json`, `tests/sql-roles.test.mjs`.

- [x] Write validation and real permission tests first; run RED with missing implementation.
- [x] Implement identity/file validation, sanitized psql transport, transactional provision/reconcile/revoke.
- [x] Run GREEN for CRUD, denied DDL/admin/foreign/security/sequence privileges, idempotence, migration drift, revoke and identity collisions.
- [ ] Commit code, policy and tests.

## Task 2: reproducible evidence and handoff

Files: `deploy/stand/tests/run-sql-roles.sh`, `deploy/stand/sql-roles.md`.

- [x] Run owned PostgreSQL tests under bounded Docker locks; remove only owned completed container.
- [x] Run complete backend build under JVM lock and existing JS/deploy checks.
- [ ] Push own branch, open PR against candidate/private-stand-v1 using Refs #169/#168, obtain exact-head CI and independent review, fix findings and hand off. Product issue stays open for shared QA/PO and actual delivery.
