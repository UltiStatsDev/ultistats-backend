import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
const api = await import("../legacy-lease.mjs").catch(() => ({}));
function fixture() {
  const child = new EventEmitter();
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.exitCode = null;
  child.stdin = {
    write(script) {
      const match = /LEGACY_LOCKED_([a-f0-9]+)/.exec(script);
      if (match)
        queueMicrotask(() =>
          child.stdout.emit(
            "data",
            Buffer.from(`LEGACY_LOCKED_${match[1]}|123|1000000.1\n`),
          ),
        );
      return true;
    },
    end() {
      child.exitCode = 0;
      queueMicrotask(() => child.emit("exit", 0, null));
    },
  };
  child.kill = () => {
    child.exitCode = 1;
    child.emit("exit", 1, null);
  };
  const state = { session: true, locks: 2, stopped: false, scripts: [] };
  const manifest = {
    backend: {
      project: "owned-legacy",
      helper: {
        databaseContainerId: "1".repeat(64),
        databaseName: "ultistats",
        databaseUser: "ultistats",
      },
    },
  };
  const run = (program, args, options = {}) => {
    if (args[0] === "inspect")
      return JSON.stringify([
        {
          Id: "1".repeat(64),
          Image: `sha256:${"2".repeat(64)}`,
          State: { Running: !state.stopped },
          Config: { Labels: { "com.docker.compose.project": "owned-legacy" } },
        },
      ]);
    if (options.input?.includes("FROM pg_tables"))
      return '["flyway_schema_history","matches"]';
    if (options.input?.includes("json_build_object"))
      return JSON.stringify({ session: state.session, locks: state.locks });
    throw new Error("UNEXPECTED_LEASE_COMMAND");
  };
  const spawnProcess = (program, args, options) => {
    state.scripts.push([program, args, options]);
    const write = child.stdin.write;
    child.stdin.write = (script) => {
      state.scripts.push(script);
      return write(script);
    };
    return child;
  };
  return { manifest, run, spawnProcess, child, state };
}
test("legacy lease locks all actual public tables and rejects a dead session", async () => {
  const f = fixture();
  const lease = await api.acquireLegacyLease(f.manifest, {
    transactionId: "transaction-001",
    run: f.run,
    spawnProcess: f.spawnProcess,
  });
  assert.match(
    f.state.scripts.find((x) => typeof x === "string"),
    /LOCK TABLE public\."flyway_schema_history",public\."matches" IN SHARE MODE/,
  );
  await lease.recheck();
  f.child.emit("exit", 1, null);
  await assert.rejects(lease.recheck, { message: "LEGACY_FENCE_LOST" });
  await lease.release();
});
test("missing table lock cannot be replaced with a claimed live receipt", async () => {
  const f = fixture();
  const lease = await api.acquireLegacyLease(f.manifest, {
    transactionId: "transaction-001",
    run: f.run,
    spawnProcess: f.spawnProcess,
  });
  f.state.locks = 1;
  await assert.rejects(lease.recheck, { message: "LEGACY_FENCE_LOST" });
  await lease.release();
});
test("only actual stopped old DB can finish legacy lease transition", async () => {
  const f = fixture();
  const lease = await api.acquireLegacyLease(f.manifest, {
    transactionId: "transaction-001",
    run: f.run,
    spawnProcess: f.spawnProcess,
  });
  await assert.rejects(lease.confirmDatabaseStopped, {
    message: "LEGACY_DATABASE_STILL_RUNNING",
  });
  f.state.stopped = true;
  f.child.emit("exit", 1, null);
  const proof = await lease.confirmDatabaseStopped();
  assert.equal(proof.stopped, true);
  await lease.release();
});
test("match appearing before SQL locks are obtained prevents successful legacy lease", async () => {
  const f = fixture();
  f.child.stdin.write = (script) => {
    const match = /LEGACY_LOCKED_([a-f0-9]+)/.exec(script);
    if (match)
      queueMicrotask(() =>
        f.child.stdout.emit(
          "data",
          Buffer.from(
            `LEGACY_ACTIVE_${match[1]}\nLEGACY_LOCKED_${match[1]}|123|1000000.1\n`,
          ),
        ),
      );
    return true;
  };
  await assert.rejects(
    () =>
      api.acquireLegacyLease(f.manifest, {
        transactionId: "transaction-001",
        run: f.run,
        spawnProcess: f.spawnProcess,
      }),
    { message: "MATCH_IN_PROGRESS" },
  );
});
test("expired or released legacy lease never authorizes a later database stop receipt", async () => {
  const f = fixture();
  const lease = await api.acquireLegacyLease(f.manifest, {
    transactionId: "transaction-001",
    run: f.run,
    spawnProcess: f.spawnProcess,
    maxLeaseMs: 100,
  });
  await new Promise((resolve) => setTimeout(resolve, 120));
  await assert.rejects(lease.recheck, { message: "LEGACY_FENCE_LOST" });
  f.state.stopped = true;
  await assert.rejects(lease.confirmDatabaseStopped, {
    message: "LEGACY_FENCE_LOST",
  });
  await lease.release();
});
