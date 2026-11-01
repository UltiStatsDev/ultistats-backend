import test from "node:test";
import assert from "node:assert/strict";

test("NEW-cluster bootstrap provider is available before installer coupling", async () => {
  const provider = await import("../sql-bootstrap.mjs").catch(() => null);
  assert.equal(
    typeof provider?.prepareSqlCluster,
    "function",
    "NEW-cluster preparation provider missing",
  );
  assert.equal(
    typeof provider?.finalizeSqlCluster,
    "function",
    "verified migration/service/frontend finalization missing",
  );
});

import { readFile, writeFile, chmod, symlink } from "node:fs/promises";
import { fixture } from "./support/bootstrap-fixture.mjs";

test("rejects UUID aliases and malformed resource identities before any Docker operation", async () => {
  const provider = await import("../sql-bootstrap.mjs");
  for (const input of [
    null,
    {},
    {
      schemaVersion: 1,
      installationId: "00000000-0000-0000-0000-000000000169",
    },
    {
      schemaVersion: 1,
      installationId: "ultistats_private",
      runtimeInstanceId: "wrong",
    },
  ])
    await assert.rejects(
      provider.prepareSqlCluster(input),
      /BOOTSTRAP_(IDENTITY|NAMESPACE|RESOURCE)_INVALID/,
    );
});

const real = process.env.STAND_SQL_BOOTSTRAP_INTEGRATION === "1";
test(
  "real NEW PostgreSQL bootstrap and actual247 Flyway/service split",
  { skip: !real, timeout: 180000 },
  async (t) => {
    const f = await fixture(process.env.STAND_BOOTSTRAP_PLAN, t);
    const provider = await import("../sql-bootstrap.mjs").catch(() => null);
    assert.equal(
      await f.sql(
        "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'us_ultistats_private_%'",
      ),
      "0",
    );
    assert.equal(
      typeof provider?.prepareSqlCluster,
      "function",
      "real NEW cluster preparation missing",
    );
    const opts = f.options;
    await t.test(
      "rejects wrong identity and existing foreign role without initialization",
      async () => {
        for (const delta of [
          { installationId: "00000000-0000-0000-0000-000000000169" },
          { runtimeInstanceId: "ffffffffffff" },
          { databaseImageId: `sha256:${"f".repeat(64)}` },
        ])
          await assert.rejects(
            provider.prepareSqlCluster({ ...opts, ...delta }),
          );
        await writeFile(
          `${f.root}/unsafe-credentials.json`,
          JSON.stringify({ password: f.passwords.postgres }),
          { mode: 0o644 },
        );
        await symlink(
          opts.adminCredentialsPath,
          `${f.root}/credential-link.json`,
        );
        for (const path of [
          `${f.root}/unsafe-credentials.json`,
          `${f.root}/credential-link.json`,
        ])
          await assert.rejects(
            provider.prepareSqlCluster({ ...opts, adminCredentialsPath: path }),
            /PRIVATE_FILE/,
          );
        const creation = JSON.parse(
          await readFile(opts.creationReceiptPath, "utf8"),
        );
        for (const [name, delta] of [
          ["foreign-driver", { volumeDriver: "foreign" }],
          ["foreign-system", { systemIdentifier: "1" }],
        ]) {
          await writeFile(
            `${f.root}/${name}.json`,
            JSON.stringify({ ...creation, ...delta }),
            { mode: 0o600 },
          );
          await assert.rejects(
            provider.prepareSqlCluster({
              ...opts,
              creationReceiptPath: `${f.root}/${name}.json`,
            }),
          );
        }
        await f.sql("CREATE DATABASE foreign_bootstrap_probe");
        try {
          await assert.rejects(provider.prepareSqlCluster(opts));
        } finally {
          await f.sql("DROP DATABASE foreign_bootstrap_probe");
        }
        await f.sql("CREATE ROLE foreign_bootstrap_probe");
        try {
          await assert.rejects(provider.prepareSqlCluster(opts));
        } finally {
          await f.sql("DROP ROLE foreign_bootstrap_probe");
        }
        assert.equal(
          await f.sql(
            "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'us_ultistats_private_%'",
          ),
          "0",
        );
      },
    );
    const prepared = await provider.prepareSqlCluster(opts);
    assert.equal(prepared.phase, "BOOTSTRAP_PREREQUISITES_APPLIED");
    assert.equal(prepared.identity.installationId, "ultistats_private");
    assert.equal(prepared.runtimeInstanceId, f.plan.runtimeInstanceId);
    assert.equal(
      (await provider.prepareSqlCluster(opts)).identity.databaseOid,
      prepared.identity.databaseOid,
    );
    await t.test(
      "partial phase and database marker mismatch never adopt or reinitialize",
      async () => {
        const journal = JSON.parse(
          await readFile(opts.bootstrapReceiptPath, "utf8"),
        );
        await writeFile(
          `${f.root}/partial.json`,
          JSON.stringify({ ...journal, phase: "NEW_CLUSTER_VERIFIED" }),
          { mode: 0o600 },
        );
        await assert.rejects(
          provider.prepareSqlCluster({
            ...opts,
            bootstrapReceiptPath: `${f.root}/partial.json`,
          }),
          /PARTIAL_STATE/,
        );
        await f.sql(
          "COMMENT ON DATABASE ultistats_private IS 'foreign-marker'",
        );
        try {
          await assert.rejects(
            provider.prepareSqlCluster(opts),
            /IDENTITY_MISMATCH/,
          );
        } finally {
          await f.sql(
            "COMMENT ON DATABASE ultistats_private IS 'ultistats-stand:ultistats_private'",
          );
        }
        assert.equal(
          (await provider.prepareSqlCluster(opts)).identity.databaseOid,
          prepared.identity.databaseOid,
        );
      },
    );
    await t.test(
      "idempotent bootstrap rejects privilege or membership drift in its service identity",
      async () => {
        await f.sql(`ALTER ROLE ${f.roles.service} SUPERUSER`);
        try {
          await assert.rejects(
            provider.prepareSqlCluster(opts),
            /ROLE_IDENTITY/,
          );
        } finally {
          await f.sql(`ALTER ROLE ${f.roles.service} NOSUPERUSER`);
        }
        await f.sql(`GRANT ${f.roles.migration} TO ${f.roles.service}`);
        try {
          await assert.rejects(
            provider.prepareSqlCluster(opts),
            /ROLE_IDENTITY/,
          );
        } finally {
          await f.sql(`REVOKE ${f.roles.migration} FROM ${f.roles.service}`);
        }
      },
    );
    await t.test(
      "separate migration owner and service DML with maintenance/template isolation",
      async () => {
        assert.equal(
          await f.authenticated(
            "migration",
            "BEGIN; CREATE TABLE owner_probe169(id integer); ROLLBACK; SELECT current_user",
          ),
          f.roles.migration,
        );
        await assert.rejects(
          f.authenticated(
            "service",
            "CREATE TABLE cannot_create169(id integer)",
          ),
        );
        for (const db of ["postgres", "template1"])
          await assert.rejects(
            f.authenticated("service", "SELECT 1", { database: db }),
          );
        await assert.rejects(
          f.authenticated("service", "SELECT 1", {
            password: "incorrect-synthetic-password",
          }),
        );
      },
    );
    const bindings = await f.startWriters();
    const bound = await provider.bindSqlSources(opts, bindings);
    assert.equal(bound.phase, "SQL_SOURCES_BOUND");
    assert.equal(bound.writerBindings.length, 2);
    await provider.bindSqlSources(opts, bindings);
    await assert.rejects(
      provider.finalizeSqlCluster(opts, {
        ...bindings,
        application: {
          containerId: bindings.applicationContainerId,
          sourceCommit: f.plan.applicationSource,
          jarSha256: f.plan.applicationJarSha256,
          jarPath: "/app/app.jar",
        },
      }),
    );
    await f.launchApplication(bindings.applicationContainerId);
    const url = await f.appUrl(bindings.applicationContainerId);
    let ready = false;
    for (let n = 0; n < 180; n++) {
      try {
        const r = await fetch(`${url}/v3/api-docs`, {
          signal: AbortSignal.timeout(500),
        });
        if (r.ok) {
          ready = true;
          break;
        }
      } catch {}
      await new Promise((r) => setTimeout(r, 250));
    }
    assert.equal(
      ready,
      true,
      "actual247 application must start with separate Flyway owner/service account",
    );
    const finalized = await provider.finalizeSqlCluster(opts, {
      ...bindings,
      application: {
        containerId: bindings.applicationContainerId,
        sourceCommit: f.plan.applicationSource,
        jarSha256: f.plan.applicationJarSha256,
        jarPath: "/app/app.jar",
      },
    });
    assert.equal(finalized.phase, "SQL_INGRESS_READY_FOR170");
    assert.equal(finalized.roleProof.state, "enabled");
    assert.equal(finalized.migrations.length, 10);
    assert.equal(
      finalized.migrations.every((m) => m.installedBy === f.roles.migration),
      true,
    );
    assert.equal(
      JSON.stringify(finalized).includes(f.passwords.service),
      false,
    );
    await t.test(
      "actual247 API reads/writes with service account while frontend stays restricted",
      async () => {
        const r = await fetch(`${url}/api/v1/teams`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            name: "Bootstrap169 synthetic",
            city: "Synthetic",
          }),
        });
        assert.equal(r.status, 201);
        const pgHost = f.plan.postgresContainer;
        const client = { from: bindings.proxyContainerId, host: pgHost };
        assert.equal(
          await f.authenticated(
            "login",
            "SELECT count(*) FROM teams WHERE name='Bootstrap169 synthetic'",
            client,
          ),
          "1",
        );
        await f.authenticated(
          "login",
          "INSERT INTO teams(id,name) VALUES (gen_random_uuid(),'SQL bootstrap169'); UPDATE teams SET city='checked' WHERE name='SQL bootstrap169'; SELECT city FROM teams WHERE name='SQL bootstrap169'; DELETE FROM teams WHERE name='SQL bootstrap169';",
          client,
        );
        for (const q of [
          "CREATE TABLE forbidden169(id integer)",
          "SELECT * FROM flyway_schema_history",
          "CREATE ROLE forbidden169",
        ])
          await assert.rejects(f.authenticated("login", q, client));
        await assert.rejects(
          f.authenticated("login", "SELECT 1", {
            ...client,
            database: "postgres",
          }),
        );
        await assert.rejects(
          f.authenticated(
            "service",
            "UPDATE flyway_schema_history SET success=false",
          ),
        );
        await assert.rejects(
          f.authenticated(
            "service",
            "CREATE TEMP TABLE service_temp169(id integer)",
          ),
        );
        await assert.rejects(
          f.authenticated("service", "CREATE ROLE service_admin169"),
        );
        await f.authenticated(
          "migration",
          "CREATE TABLE security_future169(secret text)",
        );
        await assert.rejects(
          f.authenticated("login", "SELECT * FROM security_future169", client),
        );
      },
    );
    assert.equal(
      (
        await provider.finalizeSqlCluster(opts, {
          ...bindings,
          application: {
            containerId: bindings.applicationContainerId,
            sourceCommit: f.plan.applicationSource,
            jarSha256: f.plan.applicationJarSha256,
            jarPath: "/app/app.jar",
          },
        })
      ).identity.databaseOid,
      prepared.identity.databaseOid,
    );
    await t.test(
      "completed bootstrap rejects privileged frontend identity drift",
      async () => {
        await f.sql(`ALTER ROLE ${f.roles.login} SUPERUSER`);
        try {
          await assert.rejects(
            provider.finalizeSqlCluster(opts, {
              ...bindings,
              application: {
                containerId: bindings.applicationContainerId,
                sourceCommit: f.plan.applicationSource,
                jarSha256: f.plan.applicationJarSha256,
                jarPath: "/app/app.jar",
              },
            }),
            /FRONTEND_IDENTITY/,
          );
        } finally {
          await f.sql(`ALTER ROLE ${f.roles.login} NOSUPERUSER`);
        }
      },
    );
    await t.test(
      "completed phase never returns readiness proof after effective HBA file changes",
      async () => {
        const args = [
          "exec",
          "--user",
          "postgres",
          "-i",
          opts.databaseContainerId,
          "sh",
          "-c",
          "cat > /var/lib/postgresql/data/pg_hba.conf",
        ];
        const original = await f.run([
          "exec",
          "--user",
          "postgres",
          opts.databaseContainerId,
          "cat",
          "/var/lib/postgresql/data/pg_hba.conf",
        ]);
        await f.run(args, `# changed after bootstrap\n${original}\n`);
        try {
          await assert.rejects(
            provider.finalizeSqlCluster(opts, {
              ...bindings,
              application: {
                containerId: bindings.applicationContainerId,
                sourceCommit: f.plan.applicationSource,
                jarSha256: f.plan.applicationJarSha256,
                jarPath: "/app/app.jar",
              },
            }),
            /HBA_CHANGED/,
          );
        } finally {
          await f.run(args, original + "\n");
        }
      },
    );
    const journal = JSON.parse(
      await readFile(opts.bootstrapReceiptPath, "utf8"),
    );
    assert.equal(journal.phase, "SQL_INGRESS_READY_FOR170");
    await writeFile(
      `${f.root}/actual247-evidence.json`,
      JSON.stringify(
        {
          sourceCommit: f.plan.applicationSource,
          jarSha256: f.plan.applicationJarSha256,
          applicationImageId: f.plan.applicationImageId,
          applicationContainerId: bindings.applicationContainerId,
          databaseIdentity: prepared.identity,
          migrations: finalized.migrations,
          serviceRole: f.roles.service,
          actualApiCrud: true,
          frontendPositiveNegativeChecks: true,
          partialAndCollisionChecks: true,
          hbaMutationRejected: true,
          proof: finalized,
        },
        null,
        2,
      ) + "\n",
      { mode: 0o600 },
    );
    for (const password of Object.values(f.passwords))
      assert.equal(JSON.stringify(journal).includes(password), false);
  },
);
