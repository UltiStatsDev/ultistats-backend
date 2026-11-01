import test from "node:test";
import assert from "node:assert/strict";
import {
  mkdtempSync,
  rmSync,
  writeFileSync,
  existsSync,
  readFileSync,
  mkdirSync,
  symlinkSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
const api = await import("../backup.mjs").catch(() => ({}));
function temp(t) {
  const p = mkdtempSync(join(tmpdir(), "backend161-backup-"));
  t.after(() => rmSync(p, { recursive: true, force: true }));
  return p;
}
test("uploads fingerprint detects changed photo and refuses symlink escape", (t) => {
  const p = temp(t);
  writeFileSync(join(p, "photo"), "one");
  const a = api.treeFingerprint(p);
  writeFileSync(join(p, "photo"), "two");
  assert.notEqual(api.treeFingerprint(p), a);
  symlinkSync("/etc/passwd", join(p, "escape"));
  assert.throws(() => api.treeFingerprint(p), {
    message: "UNSUPPORTED_UPLOAD_ENTRY",
  });
});
test("failed dump retains incomplete private evidence without completion or DB restore", async (t) => {
  const root = temp(t),
    backupPath = join(root, "backup");
  const calls = [];
  await assert.rejects(() =>
    api.createBackup({
      backupPath,
      manifest: {
        backend: {
          helper: {
            databaseContainerId: "1".repeat(64),
            databaseUser: "ultistats",
            databaseName: "ultistats",
            writerContainerIds: ["2".repeat(64)],
            settingsPaths: [],
          },
        },
      },
      receipt: { transactionId: "transaction-001" },
      recheck: async () => {},
      content: () => "a".repeat(64),
      run: (program, args, options = {}) => {
        calls.push([program, args]);
        if (options.input?.includes("pg_database_size")) return "1024";
        if (args[0] === "cp" && args[2] === "-") return Buffer.alloc(1024);
        throw new Error("PASSWORD_PRIVATE");
      },
    }),
  );
  assert.equal(existsSync(join(backupPath, "manifest.json")), false);
  assert.equal(existsSync(join(backupPath, "incomplete.json")), true);
  assert.equal(JSON.stringify(calls).includes("pg_restore"), false);
});
test("new writer on final quietpoint prevents successful backup manifest", async (t) => {
  const root = temp(t),
    backupPath = join(root, "backup");
  let rechecks = 0;
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
  await assert.rejects(
    () =>
      api.createBackup({
        backupPath,
        manifest,
        receipt: { transactionId: "transaction-001" },
        content: () => "a".repeat(64),
        recheck: async () => {
          if (++rechecks === 2) throw new Error("WRITER_RUNNING");
        },
        run: (program, args, options = {}) => {
          if (options.input?.includes("pg_database_size")) return "1024";
          if (args[0] === "cp" && args[2] === "-") return Buffer.alloc(1024);
          if (args.some((v) => v.includes(" pg_dump ")))
            return Buffer.from("PGDMPfixture");
          if (args.includes("pg_restore")) return "valid TOC";
          if (args[0] === "cp") {
            mkdirSync(args[2], { recursive: true });
            writeFileSync(join(args[2], "photo"), "photo");
            return "";
          }
          throw new Error("unexpected command");
        },
      }),
    { message: "WRITER_RUNNING" },
  );
  assert.equal(existsSync(join(backupPath, "manifest.json")), false);
  assert.equal(
    JSON.parse(readFileSync(join(backupPath, "incomplete.json"))).complete,
    false,
  );
});
