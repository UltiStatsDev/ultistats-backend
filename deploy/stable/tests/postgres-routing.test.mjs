import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync, chmodSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { sql } from "../observe.mjs";
import { createBackup } from "../backup.mjs";
import { acquireLegacyLease } from "../legacy-lease.mjs";

const manifest = {
  backend: {
    helper: {
      databaseContainerId: "1".repeat(64),
      databaseUser: "ultistats",
      databaseName: "ultistats",
      writerContainerIds: ["2".repeat(64)],
      settingsPaths: [],
    },
  },
};
function privateTemp(t) {
  const root = mkdtempSync(join(tmpdir(), "backend161-routing-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return root;
}
function assertLocalConnection(t, args, utility, applicationName) {
  const root = privateTemp(t);
  const executable = join(root, utility);
  writeFileSync(
    executable,
    `#!${process.execPath}\nprocess.stdout.write(JSON.stringify({argv:process.argv.slice(2),pg:Object.fromEntries(Object.entries(process.env).filter(([key])=>key.startsWith('PG')))}));\n`,
  );
  chmodSync(executable, 0o700);
  const index = args.indexOf("-c");
  assert.match(
    args[index + 1],
    /^exec \/usr\/bin\/env -i PATH=\/usr\/local\/sbin:\/usr\/local\/bin:\/usr\/sbin:\/usr\/bin:\/sbin:\/bin /,
  );
  const shell = args[index + 1].replace(` ${utility} `, ` ${executable} `);
  assert.notEqual(
    shell,
    args[index + 1],
    "replace the client before executing the local test shell",
  );
  const inherited = {
    PATH: `${root}:/usr/bin:/bin`,
    POSTGRES_PASSWORD: "synthetic-only",
    PGHOST: "foreign.example.invalid",
    PGHOSTADDR: "192.0.2.1",
    PGPORT: "6543",
    PGSERVICE: "foreign",
    PGSERVICEFILE: "/foreign/service.conf",
    PGSYSCONFDIR: "/foreign",
    PGDATABASE: "foreign",
    PGUSER: "foreign",
    PGOPTIONS: "-c search_path=foreign",
    PGPASSFILE: "/foreign/pass",
    PGSSLMODE: "require",
    PGAPPNAME: "foreign",
  };
  const actual = JSON.parse(
    execFileSync("sh", ["-c", shell, ...args.slice(index + 2)], {
      env: inherited,
      encoding: "utf8",
      stdio: ["pipe", "pipe", "pipe"],
    }),
  );
  assert.deepEqual(actual.pg, {
    PGPASSWORD: "synthetic-only",
    PGAPPNAME: applicationName,
  });
  assert.equal(actual.argv[actual.argv.indexOf("-h") + 1], "127.0.0.1");
  assert.equal(actual.argv[actual.argv.indexOf("-p") + 1], "5432");
  assert.equal(actual.argv[actual.argv.indexOf("-U") + 1], "ultistats");
  assert.equal(actual.argv[actual.argv.indexOf("-d") + 1], "ultistats");
}

test("SQL observations scrub inherited libpq routing and explicitly use the inspected local server", (t) => {
  let command;
  sql(manifest, "SELECT 1;", {
    run: (_program, args) => {
      command = args;
      return "1";
    },
  });
  assertLocalConnection(t, command, "psql", "ultistats-stable-helper");
});
test("backup pg_dump scrubs inherited libpq routing and uses the same local server", async (t) => {
  let command;
  await assert.rejects(
    () =>
      createBackup({
        backupPath: join(privateTemp(t), "backup"),
        manifest,
        receipt: { transactionId: "transaction-001" },
        recheck: async () => {},
        content: () => "a".repeat(64),
        run: (_program, args, options = {}) => {
          if (options.input?.includes("pg_database_size")) return "1024";
          if (args[0] === "cp" && args[2] === "-") return Buffer.alloc(1024);
          command = args;
          throw new Error("DUMP_CAPTURED");
        },
      }),
    { message: "DUMP_CAPTURED" },
  );
  assertLocalConnection(t, command, "pg_dump", "ultistats-stable-backup");
});
test("live legacy SQL lease scrubs inherited libpq routing and locks the same local server", async (t) => {
  let command;
  await assert.rejects(
    () =>
      acquireLegacyLease(manifest, {
        transactionId: "transaction-001",
        run: () => '["matches"]',
        spawnProcess: (_program, args) => {
          command = args;
          throw new Error("LEASE_CAPTURED");
        },
      }),
    { message: "LEASE_CAPTURED" },
  );
  assertLocalConnection(t, command, "psql", command.at(-1));
});
