// OS-boundary simulation: real CLI lifecycle/files/guards, no Docker runtime claim.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  readdirSync,
  rmSync,
  existsSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { executeRequest } from "../backend-release.mjs";
import { hash, flywayChecksum, readPrivateJson } from "../helper-contract.mjs";
import { maintenanceConfig } from "../observe.mjs";
import { fixture } from "./support/runtime-fixture.mjs";
test("complete fenced backup target postflight release preserves data and private receipts", async (t) => {
  const f = fixture(t);
  f.api.State.Running = false;
  const acquired = await f.call("fence-acquire");
  assert.equal(acquired.evidence.quiet, true);
  const backed = await f.call("backup");
  assert.equal(backed.evidence.complete, true);
  assert.equal(f.state.dumpCount, 1);
  f.m.release.version = "v0.1.0";
  f.write(f.manifestPath, f.m);
  f.api.State.Running = true;
  const checked = await f.call("postflight");
  assert.equal(checked.evidence.preserved, true);
  const released = await f.call("fence-release");
  assert.equal(released.evidence.released, true);
  assert.equal(
    readPrivateJson(released.evidence.releasePath).transactionId,
    "transaction-001",
  );
  f.state.activeMatches = 1;
  await assert.rejects(() => f.call("fence-release"), {
    message: "MATCH_IN_PROGRESS",
  });
});
test("writer appearing after inspect blocks acquire before dumping data", async (t) => {
  const f = fixture(t);
  await assert.rejects(() => f.call("fence-acquire"), {
    message: "WRITER_RUNNING",
  });
  assert.equal(existsSync(f.fencePath), false);
  assert.equal(f.state.dumpCount, 0);
});
test("extra network client and dirty source never reach backup", async (t) => {
  const f = fixture(t);
  f.api.State.Running = false;
  f.state.extraNetworkClient = true;
  await assert.rejects(() => f.call("fence-acquire"), {
    message: "UNKNOWN_NETWORK_CLIENT",
  });
  f.state.extraNetworkClient = false;
  f.state.dirty = true;
  await assert.rejects(() => f.call("fence-acquire"), {
    message: "SOURCE_NOT_CLEAN",
  });
  assert.equal(f.state.dumpCount, 0);
});
test("rollback previous-mode proof uses separate receipts and refuses changed database", async (t) => {
  const f = fixture(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  await f.call("backup");
  const previousManifestPath = join(f.root, "previous.json");
  f.write(previousManifestPath, f.m);
  const planner = await f.call("rollback-plan", { previousManifestPath });
  assert.equal(planner.evidence.compatible, true);
  f.api.State.Running = true;
  const checked = await f.call("postflight", {
    rollbackPlanPath: planner.evidence.rollbackPlanPath,
  });
  assert.match(checked.evidence.postflightPath, /rollback-postflight\.json$/);
  const released = await f.call("fence-release", {
    rollbackPlanPath: planner.evidence.rollbackPlanPath,
  });
  assert.match(released.evidence.releasePath, /rollback-released\.json$/);
});
test("already installed exact target can inspect for no-op without being listed as previous", async (t) => {
  const f = fixture(t);
  f.record.compatibility.supportedSourcePairs = [
    {
      web: "39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba",
      backend: "24704e11143a0bf919559a41324603f936ae03cf",
    },
  ];
  f.write(f.recordPath, f.record);
  f.base.targetRecordSha256 = hash(readFileSync(f.recordPath));
  f.m.release.version = "v0.1.0";
  f.write(f.manifestPath, f.m);
  f.state.open = true;
  assert.equal((await f.call("inspect")).evidence.ready, true);
  assert.equal(f.state.dumpCount, 0);
});
test("readonly runtime identity observer supports isolated dev without granting update ownership", async (t) => {
  const f = fixture(t);
  f.m.mode = "dev";
  f.m.backend.project = "ultistats-dev-abc123456789";
  f.api.Config.Labels["com.docker.compose.project"] = f.m.backend.project;
  const { observeRuntimeIdentity } = await import("../observe.mjs");
  const identity = await observeRuntimeIdentity({
    installation: f.m,
    run: f.run,
    fetcher: f.fetcher,
  });
  assert.match(identity.jarSha256, /^[a-f0-9]{64}$/);
  f.write(f.manifestPath, f.m);
  await assert.rejects(() => f.call("inspect"), {
    message: "UNSUPPORTED_INSTALLATION",
  });
});
test("forged compatible rollback receipt cannot substitute an unlisted previous pair", async (t) => {
  const f = fixture(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  await f.call("backup");
  const previousManifestPath = join(f.root, "previous.json");
  f.write(previousManifestPath, f.m);
  const result = await f.call("rollback-plan", { previousManifestPath });
  const planner = readPrivateJson(result.evidence.rollbackPlanPath);
  planner.previous.web = "b".repeat(40);
  f.write(result.evidence.rollbackPlanPath, planner);
  f.m.release.webSource = planner.previous.web;
  f.write(f.manifestPath, f.m);
  f.api.State.Running = true;
  await assert.rejects(
    () =>
      f.call("postflight", {
        rollbackPlanPath: result.evidence.rollbackPlanPath,
      }),
    { message: "ROLLBACK_UNPROVEN" },
  );
});
test("normal readonly startup identity accepts an ongoing match while updater guards still reject it", async (t) => {
  const f = fixture(t);
  f.state.activeMatches = 1;
  const { observeRuntimeIdentity } = await import("../observe.mjs");
  const result = await observeRuntimeIdentity({
    installation: f.m,
    run: f.run,
    fetcher: f.fetcher,
  });
  assert.match(result.migrationFingerprint, /^[a-f0-9]{64}$/);
  await assert.rejects(() => f.call("inspect"), {
    message: "MATCH_IN_PROGRESS",
  });
  f.api.State.Running = false;
  await assert.rejects(() => f.call("fence-acquire"), {
    message: "MATCH_IN_PROGRESS",
  });
  assert.equal(f.state.dumpCount, 0);
});
test("declared immutable PostgreSQL image must match observed owned database container", async (t) => {
  const f = fixture(t);
  f.m.backend.databaseImageId = `sha256:${"9".repeat(64)}`;
  f.write(f.manifestPath, f.m);
  f.state.open = true;
  await assert.rejects(() => f.call("inspect"), {
    message: "IDENTITY_MISMATCH",
  });
  assert.equal(f.state.dumpCount, 0);
});
test("actual PostgreSQL inet /32 representation recognizes owned API pool and still blocks foreign clients", async (t) => {
  const f = fixture(t);
  f.state.postgresInetClients = true;
  f.state.open = true;
  assert.equal((await f.call("inspect")).evidence.ready, true);
  f.state.otherClients = 1;
  await assert.rejects(() => f.call("inspect"), {
    message: "RUNTIME_UNPROVEN",
  });
  assert.equal(f.state.dumpCount, 0);
});
