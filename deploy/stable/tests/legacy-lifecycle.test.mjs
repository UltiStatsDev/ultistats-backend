// Whole private-file legacy lifecycle with OS boundaries simulated; real PG proof belongs162.
import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { execFileSync } from "node:child_process";
import { mkdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fixture } from "./support/runtime-fixture.mjs";
import {
  inspectLegacyAdoption,
  backupLegacyAdoption,
  verifyLegacyTransition,
  completeLegacyAdoption,
} from "../legacy-adoption.mjs";
const BASELINE = "24704e11143a0bf919559a41324603f936ae03cf",
  WEB = "39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba";
function legacy(t) {
  const f = fixture(t, { legacy: true, sourceSha: BASELINE }),
    root = join(f.root, "old-web"),
    project = f.m.backend.project,
    commands = [];
  execFileSync("git", ["-C", root, "init", "--quiet"], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  f.m.root = root;
  f.m.installationRoot = join(f.root, "control");
  mkdirSync(f.m.installationRoot, { mode: 0o700 });
  const backendControl = join(f.root, "backend-control");
  mkdirSync(backendControl, { mode: 0o700 });
  const controllerSha = (path) => {
    execFileSync("git", ["-C", path, "init", "--quiet"], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    execFileSync(
      "git",
      [
        "-C",
        path,
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "commit",
        "--allow-empty",
        "-qm",
        "private controller fixture",
      ],
      { stdio: ["ignore", "pipe", "pipe"] },
    );
    return execFileSync("git", ["-C", path, "rev-parse", "HEAD"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  };
  f.m.tooling = {
    webRoot: f.m.installationRoot,
    webSource: controllerSha(f.m.installationRoot),
    backendRoot: backendControl,
    backendSource: controllerSha(backendControl),
  };
  f.m.web = { origin: "http://127.0.0.1:25462" };
  f.m.release.webSource = WEB;
  f.m.backend.source = f.source;
  f.m.backend.databaseVolume = `${project}_database`;
  f.m.backend.uploadsVolume = `${project}_uploads`;
  f.db.Mounts[0].Name = f.m.backend.databaseVolume;
  f.api.Mounts[0].Name = f.m.backend.uploadsVolume;
  f.api.Config.Labels = {
    ...f.api.Config.Labels,
    "com.docker.compose.service": "api",
    "com.docker.compose.project.working_dir": root,
  };
  f.db.Config.Labels = {
    ...f.db.Config.Labels,
    "com.docker.compose.service": "postgres",
    "com.docker.compose.project.working_dir": root,
  };
  f.db.Config.Env = [
    "POSTGRES_DB=ultistats",
    "POSTGRES_USER=ultistats",
    "POSTGRES_PASSWORD=SECRET_NEVER_LOG",
  ];
  f.api.HostConfig.PortBindings = {
    "8080/tcp": [{ HostIp: "127.0.0.1", HostPort: "25461" }],
  };
  const ownerDir = join(root, ".git/ultistats");
  mkdirSync(ownerDir, { mode: 0o700 });
  const ownerPath = join(ownerDir, "stable-owner.json");
  f.m.legacy = {
    schemaVersion: 1,
    root,
    apiContainer: f.api.Id,
    dbContainer: f.db.Id,
    imageId: f.api.Image,
    ownerPath,
  };
  f.write(ownerPath, {
    instanceId: f.m.instanceId,
    installationRoot: f.m.installationRoot,
    apiContainer: f.api.Id,
    dbContainer: f.db.Id,
  });
  const claim = {
    schemaVersion: 1,
    project,
    legacyRoot: root,
    apiContainer: f.api.Id,
    dbContainer: f.db.Id,
    webSource: WEB,
    backendSource: BASELINE,
    apiOrigin: f.m.backend.origin,
    webOrigin: f.m.web.origin,
    expectedJarSha256: f.m.backend.helper.jarSha256,
    databaseName: "ultistats",
    databaseUser: "ultistats",
    settingsPaths: f.m.backend.helper.settingsPaths,
  };
  const volumes = [
    {
      Name: f.m.backend.databaseVolume,
      CreatedAt: "2026-11-02T00:00:00Z",
      Driver: "local",
    },
    {
      Name: f.m.backend.uploadsVolume,
      CreatedAt: "2026-11-02T00:00:00Z",
      Driver: "local",
    },
  ];
  const state = {
    phase: "legacy",
    session: true,
    locks: 3,
    killDuringDump: false,
    child: undefined,
    newDB: undefined,
    newAPI: undefined,
  };
  const run = (program, args, options = {}) => {
    commands.push([program, args]);
    if (program === "docker") {
      if (args[0] === "ps") return `${f.api.Id}\n${f.db.Id}\n`;
      if (args[0] === "volume")
        return JSON.stringify([volumes.find((v) => v.Name === args[2])]);
      if (args[0] === "network")
        return JSON.stringify([
          {
            Name: f.network,
            Internal: state.phase === "managed",
            Containers: Object.fromEntries(
              (state.phase === "legacy"
                ? [f.db.Id, ...(f.api.State.Running ? [f.api.Id] : [])]
                : [state.newDB.Id, state.newAPI.Id, f.ingress.Id]
              ).map((id) => [id, {}]),
            ),
          },
        ]);
      if (args[0] === "inspect" && state.newDB?.Id === args[1])
        return JSON.stringify([state.newDB]);
      if (args[0] === "inspect" && state.newAPI?.Id === args[1])
        return JSON.stringify([state.newAPI]);
      if (options.input?.includes("current_setting")) return "16";
      if (options.input?.includes("'columns'"))
        return JSON.stringify({
          columns: [
            {
              table_name: "matches",
              column_name: "id",
              data_type: state.catalogChanged ? "text" : "uuid",
            },
          ],
          constraints: [],
          indexes: [],
        });
      if (options.input?.includes("json_build_object"))
        return JSON.stringify({ session: state.session, locks: state.locks });
      if (
        options.input?.includes("FROM pg_tables") &&
        !options.input.includes("tablename<>")
      )
        return '["flyway_schema_history","matches","undo_command_receipts"]';
      if (state.killDuringDump && args.some((v) => v.includes(" pg_dump ")))
        state.child.emit("exit", 1, null);
    }
    return f.run(program, args, options);
  };
  const spawnProcess = () => {
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
    child.kill = () => child.emit("exit", 1, null);
    state.child = child;
    return child;
  };
  f.state.open = true;
  const options = { run, spawnProcess, fetcher: f.fetcher };
  const args = {
    installation: f.m,
    claim,
    backupPath: f.backupPath,
    evidenceDirectory: f.root,
    transactionId: "transaction-001",
  };
  async function transition(backup) {
    await backup.lease.recheck();
    f.db.State.Running = false;
    state.child.emit("exit", 1, null);
    await backup.lease.confirmDatabaseStopped();
    const managed = structuredClone(f.m);
    delete managed.legacy;
    state.newDB = structuredClone(f.db);
    state.newDB.Id = "7".repeat(64);
    state.newDB.State.Running = true;
    state.newAPI = structuredClone(f.api);
    state.newAPI.Id = "8".repeat(64);
    state.newAPI.State.Running = true;
    state.newAPI.HostConfig.PortBindings = {};
    managed.backend.helper.databaseContainerId = state.newDB.Id;
    managed.backend.helper.writerContainerIds = [state.newAPI.Id];
    state.phase = "managed";
    f.state.open = false;
    return {
      installation: managed,
      backupPath: f.backupPath,
      evidenceDirectory: f.root,
      transactionId: "transaction-001",
    };
  }
  return { f, args, options, state, volumes, commands, transition };
}
test("legacy private backup holds live lease until old PG stops, then verifies same data behind gate", async (t) => {
  const l = legacy(t);
  assert.equal(
    (await inspectLegacyAdoption(l.args, l.options)).disposition,
    "ok",
  );
  l.f.api.State.Running = false;
  const backup = await backupLegacyAdoption(l.args, l.options);
  assert.equal(backup.disposition, "ok");
  assert.equal(backup.evidence.complete, true);
  assert.throws(() => JSON.stringify(backup.lease), {
    message: "LEGACY_FENCE_HANDLE_NOT_SERIALIZABLE",
  });
  const transitioned = await l.transition(backup);
  const checked = await verifyLegacyTransition(transitioned, l.options);
  assert.equal(checked.disposition, "ok");
  assert.equal(checked.evidence.preserved, true);
  assert.equal(existsSync(checked.evidence.transitionPath), true);
  const completed = await completeLegacyAdoption(transitioned, l.options);
  assert.equal(completed.disposition, "ok");
  assert.equal(completed.operation, "legacy-release");
  assert.equal(completed.evidence.released, true);
  assert.equal(completed.evidence.current.backend, BASELINE);
  assert.equal(completed.evidence.current.web, WEB);
  assert.equal(completed.evidence.current.version, "unpublished");
  assert.equal(existsSync(completed.evidence.releasePath), true);
  assert.equal(l.f.state.open, false);
  l.volumes[0].CreatedAt = "2026-11-03T00:00:00Z";
  const replaced = await verifyLegacyTransition(transitioned, l.options);
  assert.equal(replaced.disposition, "maintenance_required");
  assert.equal(replaced.reason, "LEGACY_STORAGE_UNPROVEN");
  await backup.lease.release();
});
test("dead SQL lease during dump leaves incomplete evidence and never claims successful adoption", async (t) => {
  const l = legacy(t);
  l.f.api.State.Running = false;
  l.state.killDuringDump = true;
  const result = await backupLegacyAdoption(l.args, l.options);
  assert.equal(result.disposition, "maintenance_required");
  assert.equal(result.reason, "LEGACY_FENCE_LOST");
  assert.equal(existsSync(join(l.f.backupPath, "manifest.json")), false);
  assert.equal(existsSync(join(l.f.backupPath, "incomplete.json")), true);
  assert.equal(l.f.db.State.Running, true);
});
test("legacy completion rechecks active matches instead of trusting postflight receipt", async (t) => {
  const l = legacy(t);
  l.f.api.State.Running = false;
  const backup = await backupLegacyAdoption(l.args, l.options);
  assert.equal(backup.disposition, "ok");
  const transitioned = await l.transition(backup);
  assert.equal(
    (await verifyLegacyTransition(transitioned, l.options)).disposition,
    "ok",
  );
  l.f.state.activeMatches = 1;
  const result = await completeLegacyAdoption(transitioned, l.options);
  assert.equal(result.disposition, "maintenance_required");
  assert.equal(result.reason, "MATCH_IN_PROGRESS");
  assert.equal(
    existsSync(join(l.f.root, "transaction-001.legacy-released.json")),
    false,
  );
});
test("same rows and V9 migration history cannot conceal a changed catalog after transition", async (t) => {
  const l = legacy(t);
  l.f.api.State.Running = false;
  const backup = await backupLegacyAdoption(l.args, l.options);
  assert.equal(backup.disposition, "ok");
  const transitioned = await l.transition(backup);
  l.state.catalogChanged = true;
  const result = await verifyLegacyTransition(transitioned, l.options);
  assert.equal(result.disposition, "maintenance_required");
  assert.equal(result.reason, "DATABASE_CATALOG_CHANGED");
  assert.equal(
    existsSync(join(l.f.root, "transaction-001.legacy-postflight.json")),
    false,
  );
});
