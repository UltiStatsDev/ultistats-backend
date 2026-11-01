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
