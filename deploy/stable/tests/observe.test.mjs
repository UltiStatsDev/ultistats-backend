import test from "node:test";
import assert from "node:assert/strict";
const api = await import("../observe.mjs").catch(() => ({}));
test("observed Flyway history refuses missing failed changed or future migrations", () => {
  const expected = [
    { version: "1", script: "V1__create.sql", checksum: 1 },
    { version: "9", script: "V9__undo.sql", checksum: -2 },
  ];
  const good = expected.map((r) => ({ ...r, success: true }));
  assert.equal(api.verifyMigrations(expected, good), true);
  for (const rows of [
    good.slice(0, 1),
    [
      ...good,
      { version: "10", script: "V10__future.sql", checksum: 3, success: true },
    ],
    good.map((r, i) => ({ ...r, checksum: i ? 42 : r.checksum })),
    good.map((r) => ({ ...r, success: false })),
  ])
    assert.equal(api.verifyMigrations(expected, rows), false);
});
test("maintenance config has no alternate upstream and binds transaction", () => {
  const expected =
    'events {}\nhttp {\n  server {\n    listen 8080;\n    add_header X-UltiStats-Instance "abc123456789" always;\n    add_header X-UltiStats-Maintenance "transaction-001" always;\n    location / { return 503; }\n  }\n}\n';
  assert.equal(
    api.maintenanceConfig("abc123456789", "transaction-001"),
    expected,
  );
  assert.throws(() =>
    api.maintenanceConfig("abc123456789", 'injected";proxy_pass'),
  );
});
test("DB content query covers every public table including receipts and deleted rows", () => {
  const sql = api.contentQuery(["events", "undo_command_receipts"]);
  assert.match(sql, /undo_command_receipts/);
  assert.match(sql, /events/);
  assert.doesNotMatch(sql, /deleted_at IS NULL/);
  assert.throws(() => api.contentQuery(["x; DROP TABLE matches"]));
});
test("empty truncated or malformed SQL counts never grant quiet point", () => {
  for (const value of ["", " ", "-1", "NaN", "0\n0", "1.5"])
    assert.throws(() => api.parseCount(value), { message: "DATABASE_UNREADY" });
  assert.equal(api.parseCount("0"), 0);
  assert.equal(api.parseCount("123"), 123);
});
test("local Git replace refs cannot disguise changed product source as baseline", async (t) => {
  const { mkdtempSync, rmSync, writeFileSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const { join, resolve } = await import("node:path");
  const { execFileSync } = await import("node:child_process");
  const root = mkdtempSync(join(tmpdir(), "backend161-replace-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const repo = join(root, "repo");
  const git = (...args) =>
    execFileSync("git", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  const baseline = "24704e11143a0bf919559a41324603f936ae03cf";
  git("clone", "--shared", "--no-checkout", resolve("."), repo);
  git("-C", repo, "checkout", "--detach", baseline);
  writeFileSync(
    join(repo, "src/main/resources/application.yaml"),
    "tampered product\n",
  );
  git("-C", repo, "add", "src/main/resources/application.yaml");
  git(
    "-C",
    repo,
    "-c",
    "user.name=Helper Test",
    "-c",
    "user.email=helper@example.invalid",
    "commit",
    "-qm",
    "tampered source",
  );
  const sha = git("-C", repo, "rev-parse", "HEAD");
  git("-C", repo, "replace", sha, baseline);
  git("-C", repo, "read-tree", baseline);
  git("-C", repo, "checkout-index", "--all", "--force");
  assert.equal(git("-C", repo, "status", "--porcelain=v1"), "");
  const manifest = {
    instanceId: "abc123456789",
    backend: {
      revision: sha,
      imageId: `sha256:${"1".repeat(64)}`,
      helper: { sourceCheckout: repo },
    },
  };
  const run = (program, args) =>
    program === "git"
      ? git(...args)
      : JSON.stringify([
          {
            Id: manifest.backend.imageId,
            Config: {
              Labels: {
                "org.opencontainers.image.revision": sha,
                "io.ultistats.instance": manifest.instanceId,
              },
            },
          },
        ]);
  assert.throws(
    () => api.verifySourceIdentity(manifest, { run }),
    /SOURCE_NOT_CLEAN|PRODUCT_PARITY_MISMATCH/,
  );
});
