# Restricted stand SQL login

Refs [Backend #169](https://github.com/UltiStatsDev/ultistats-backend/issues/169), [Backend #168](https://github.com/UltiStatsDev/ultistats-backend/issues/168), [Web #121](https://github.com/UltiStatsDev/ultistats-web-ui/issues/121).

This source module is optional stand tooling. It does not install a stand, publish a stable release, change human authentication or grant network access. The first application baseline remains the finalized Web39/API247 pair plus separately reviewed tooling.

## Installer prerequisites

Use a dedicated owned PostgreSQL cluster, not a shared application database. Record the exact `pg_control_system().system_identifier`, database OID and owner in a private installation manifest. The database owner must be `migrationRole`; `serviceRole` is a different existing account. The SQL login is `us_<installationId>_sql`, separate from both. Database comment must be `ultistats-stand:<installationId>`.

The installer restricts PUBLIC CONNECT/TEMP/CREATE on every other connectable database, including maintenance/template databases, and allows only the intended migration/service access. The module verifies this boundary and refuses to modify foreign database ACLs. New databases require the same isolation before access is reopened. Only the public application schema is supported; extra application schemas require a reviewed policy extension. Missing allowlisted tables cause transaction rollback.

A checked proxy/HBA/network arrangement must make the transaction-aware SQL proxy the sole developer ingress. PostgreSQL must not have a second host/LAN/public path. The module does not modify network, HBA, container or Tailscale settings. Keep HTTP and SQL maintenance gates closed until role reconciliation and provider checks succeed.

## API and secrets

Import `provisionSqlRole`, `reconcileSqlRole`, `revokeSqlRole` and optionally `rolePolicySha256` from `sql-roles.mjs`. Provision and reconcile have the same idempotent semantics.

```js
const proof = await reconcileSqlRole({
    identity: {
        schemaVersion: 1,
        installationId, database, databaseOid, systemIdentifier,
        migrationRole, serviceRole, loginRole,
    },
    adminCredentialsPath,
    loginCredentialsPath,
});
```

Identity OID/system identifier are decimal **strings**, not JavaScript numbers. The caller supplies trusted local paths, never request-controlled executable paths. A trusted `psqlPath` override is available for packaged psql or a container adapter; default is `psql`. Node 22 and PostgreSQL 16 psql/server are the tested baseline.

Admin JSON is `{host,port,database,user,password,sslmode}`; `port` is a number and `sslmode` is explicitly `disable`, `require`, `verify-ca` or `verify-full`. Select transport settings for the actual trusted topology. Login JSON is `{password}`. Use separate private owner-only regular files, outside Git; symlinks/group/world permissions are refused. Generate a random printable ASCII login password of at least 20 characters and deliver it privately. Never place passwords in command arguments, public evidence or source control.

Admin authentication uses an ephemeral 0600 pgpass file in a 0700 directory beside the admin credential file; adapters must see that same directory. The directory is removed on success/failure. Login plaintext never enters SQL: Node derives a SCRAM-SHA-256 verifier. Server error output is intentionally suppressed, because it may contain private data/verifiers; failures contain only a sanitized error. PostgreSQL diagnostic logs and the credential directory remain private.

Proof contains `schemaVersion`, `installationId`, `database`, `databaseOid`, `systemIdentifier`, `loginRole`, `rolePolicySha256`, `state: enabled|revoked`. Treat runtime identities/proof as private operational data. Bind its policy hash and identity to the maintenance provider's exact proxy/database manifest. Proof is a completed role operation, not a transaction quiet point or readiness receipt.

## Permissions and migrations

`sql-role-policy-v1.json` explicitly lists the eleven V1–V9 application tables. Only SELECT/INSERT/UPDATE/DELETE is granted; no ownership, memberships, grant option, schema CREATE, temporary tables, TRUNCATE, role/database administration, replication or BYPASSRLS. The baseline uses UUID identifiers and has **no application sequences**: the sequence allowlist is empty, and sequence access is denied. A future sequence requires a reviewed explicit policy entry, hash change and real positive `nextval` coverage before release; no default sequence wildcard grant exists.

Reconciliation validates installation identity before mutations and serializes concurrent operations with a transaction advisory lock. A pre-existing login must have this installation's marker and no ownership/memberships. Unsafe collisions, foreign database grants, unmanaged default ACLs and unknown schemas are refused without adoption. Reconciliation removes current table/column/sequence/function grants from the login and PUBLIC, removes migration/service global and public-schema default grants to them, then grants only the policy. Migration/service effective table, column, sequence and function privileges are preserved explicitly before removing PUBLIC access. Column-only rights stay column-only; previously denied rights are not added. Their existing direct grants and ownership remain. Non-public catalog/schema/function ACLs are checked against builtin initdb privileges, with explicit standard information-schema SELECT/USAGE; unexpected sensitive catalog, column, function, schema, administrative and foreign-object grants refuse a successful proof. Unexpected application objects in system schemas are rejected. Review any migration introducing PUBLIC grants, schemas, credentials, sessions, security objects or function privileges before reopening clients.

After each deployment migration, reconcile before SQL ingress reopens. Future tables, including auth/security objects, receive no grant from this module. A new policy is a separate reviewed client/permissions compatibility decision; do not silently add credentials/session objects or copy auth tables into this v1 list.

`revokeSqlRole({...options, loginCredentialsPath: undefined})` disables login and removes the role's database/schema/data/function access in one transaction. New DML on an existing idle connection is denied. It does not cancel/terminate queries, abort open transactions, or claim that already running work stopped: the maintenance provider must fence new work and drain/defer at its bounded safe boundary before invoking role changes. After a rejected operation retain the maintenance/network fence and report the failure; no successful permission proof exists. Revoking SQL credentials and revoking the separate private-network invitation are separate owner operations.

## Verification

```sh
node --test deploy/stand/tests/sql-roles.test.mjs
bash deploy/stand/tests/run-sql-roles.sh
```

The first command exercises source/secret validation and explicitly skips real PG without a fixture. The second requires Docker, openssl, flock and Node (`NODE_BIN` may select it); it creates a uniquely named disposable PostgreSQL 16 container with no external network or published ports and disposable tmpfs PGDATA (verified through Docker HostConfig). It applies actual V1–V9 migrations, uses synthetic data/private generated passwords and checks CRUD, denied DDL/admin/foreign/security/sequence/function/column access, service preservation, idempotence, future tables, identity mismatch, role membership, and same-session revoke/re-enable. This is required for permission evidence; H2/source-only checks are insufficient.

The runner holds the Docker operation lock only around exact lifecycle operations, waits at most 60 seconds for each lock, and removes its own container and disposable fixture after test consumer END. It never prunes Docker, removes foreign resources or deletes persistent application data. An abnormal forced runner kill can leave its uniquely labeled fixture: identify exact ownership and consumer END before cleanup.

Observed implementation evidence: initial real PostgreSQL run failed because provisioning was absent; implemented permission suite passed. Separate regressions failed on service function EXECUTE, catalog ACL leakage and service table/column PUBLIC access; the final ten-test real PostgreSQL suite passed after their fixes. Actual installation, peer connectivity, shared QA and PO acceptance remain separate gates; keep product issues open.
