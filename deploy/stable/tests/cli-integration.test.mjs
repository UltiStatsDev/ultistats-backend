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
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "backend161-lifecycle-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const source = resolve("."),
    sha = execFileSync("git", ["rev-parse", "HEAD"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  const ids = {
      db: "1".repeat(64),
      api: "2".repeat(64),
      ingress: "3".repeat(64),
    },
    image = `sha256:${"4".repeat(64)}`,
    ingressImage = `sha256:${"5".repeat(64)}`,
    network = "ultistats-stable-abc123456789_backend",
    edge = "ultistats-stable-abc123456789_edge";
  const config = join(root, "ingress.conf"),
    transactionId = "transaction-001",
    instanceId = "abc123456789",
    project = `ultistats-stable-${instanceId}`;
  const migrationDir = join(source, "src/main/resources/db/migration");
  const rows = readdirSync(migrationDir)
    .filter((n) => /^V.*\.sql$/.test(n))
    .map((script) => ({
      version: script.match(/^V([^_]+(?:_[0-9]+)?)__/)[1].replaceAll("_", "."),
      script,
      checksum: flywayChecksum(
        readFileSync(join(migrationDir, script), "utf8"),
      ),
      success: true,
    }));
  const fingerprint = hash(
    JSON.stringify(
      [...rows].sort((a, b) => a.version.localeCompare(b.version)),
    ),
  );
  const settings = join(root, "settings.env");
  writeFileSync(settings, "PRIVATE=fixture\n", { mode: 0o600 });
  const m = {
    schemaVersion: 1,
    mode: "stable",
    instanceId,
    backend: {
      ownership: "managed",
      origin: "http://127.0.0.1:25461",
      project,
      revision: sha,
      imageId: image,
      databaseVolume: "owned-pg",
      uploadsVolume: "owned-uploads",
      ingress: {
        configPath: config,
        containerId: ids.ingress,
        imageId: ingressImage,
        backendNetwork: network,
        edgeNetwork: edge,
      },
      helper: {
        schemaVersion: 1,
        databaseContainerId: ids.db,
        writerContainerIds: [ids.api],
        databaseName: "ultistats",
        databaseUser: "ultistats",
        databaseNetwork: network,
        uploadsMount: "/app/uploads",
        databaseMount: "/var/lib/postgresql/data",
        settingsPaths: [settings],
        productBaseline: "24704e11143a0bf919559a41324603f936ae03cf",
        migrationFingerprint: fingerprint,
        jarSha256: hash("synthetic-jar"),
        sourceCheckout: source,
      },
    },
    release: {
      version: "0.0.0",
      webSource: "a".repeat(40),
      backendSource: sha,
    },
  };
  const labels = {
    "com.docker.compose.project": project,
    "io.ultistats.instance": instanceId,
  };
  const container = (id, img, mounts, networks) => ({
    Id: id,
    Image: img,
    State: { Running: true },
    Config: { Labels: labels, Env: [] },
    HostConfig: { PortBindings: {} },
    Mounts: mounts,
    NetworkSettings: { Networks: networks },
  });
  const db = container(
    ids.db,
    `sha256:${"6".repeat(64)}`,
    [
      {
        Destination: "/var/lib/postgresql/data",
        Name: "owned-pg",
        Type: "volume",
        RW: true,
      },
    ],
    { [network]: { Aliases: ["postgres"], IPAddress: "172.30.0.2" } },
  );
  const api = container(
    ids.api,
    image,
    [
      {
        Destination: "/app/uploads",
        Name: "owned-uploads",
        Type: "volume",
        RW: true,
      },
    ],
    { [network]: { Aliases: ["api"], IPAddress: "172.30.0.3" } },
  );
  api.Path = "java";
  api.Args = ["-jar", "app.jar"];
  api.Config.Env = [
    "DATABASE_URL=jdbc:postgresql://postgres:5432/ultistats",
    "DATABASE_USERNAME=ultistats",
    "DATABASE_PASSWORD=SECRET_NEVER_LOG",
    "APP_STORAGE_ROOT=/app/uploads",
  ];
  const ingress = container(
    ids.ingress,
    ingressImage,
    [{ Destination: "/etc/ultistats", Type: "bind", RW: false, Source: root }],
    { [network]: {}, [edge]: {} },
  );
  ingress.HostConfig.PortBindings = {
    "8080/tcp": [{ HostIp: "127.0.0.1", HostPort: "25461" }],
  };
  const state = {
    activeMatches: 0,
    otherClients: 0,
    extraNetworkClient: false,
    dirty: false,
    throwDump: false,
    dumpCount: 0,
  };
  const write = (path, value) =>
    writeFileSync(path, JSON.stringify(value) + "\n", { mode: 0o600 });
  const manifestPath = join(root, "installed.json"),
    recordPath = join(root, "record.json"),
    fencePath = join(root, "fence.json"),
    backupPath = join(root, "backup");
  const record = {
    schemaVersion: 1,
    status: "finalized",
    version: "0.1.0",
    repositories: {
      web: {
        repository: "UltiStatsDev/ultistats-web-ui",
        commit: "a".repeat(40),
        tag: "v0.1.0",
      },
      backend: {
        repository: "UltiStatsDev/ultistats-backend",
        commit: sha,
        tag: "v0.1.0",
      },
    },
    backendPin: sha,
    compatibility: {
      fromSchema: 9,
      toSchema: 9,
      appRollback: true,
      supportedSourcePairs: [{ web: "a".repeat(40), backend: sha }],
    },
  };
  write(manifestPath, m);
  write(recordPath, record);
  writeFileSync(config, maintenanceConfig(instanceId, transactionId), {
    mode: 0o600,
  });
  const base = {
    protocolVersion: 1,
    transactionId,
    installationManifestPath: manifestPath,
    targetRecordPath: recordPath,
    targetRecordSha256: hash(readFileSync(recordPath)),
    evidenceDirectory: root,
  };
  const run = (program, args, options = {}) => {
    if (program === "git") {
      if (args.includes("status")) return state.dirty ? " M src/main/file" : "";
      return execFileSync(program, args, {
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
      });
    }
    if (program !== "docker") throw new Error("UNEXPECTED_PROGRAM");
    if (args[0] === "inspect")
      return JSON.stringify([
        { [ids.db]: db, [ids.api]: api, [ids.ingress]: ingress }[args[1]],
      ]);
    if (args[0] === "network")
      return JSON.stringify([
        {
          Name: network,
          Internal: true,
          Containers: Object.fromEntries(
            [
              ids.db,
              ids.ingress,
              ...(api.State.Running ? [ids.api] : []),
              ...(state.extraNetworkClient ? ["9".repeat(64)] : []),
            ].map((id) => [id, {}]),
          ),
        },
      ]);
    if (args[0] === "image")
      return JSON.stringify([
        {
          Id: image,
          Config: {
            Labels: {
              "io.ultistats.instance": instanceId,
              "org.opencontainers.image.revision": sha,
            },
          },
        },
      ]);
    if (args[0] === "cp") {
      if (args[1].endsWith("/app.jar")) writeFileSync(args[2], "synthetic-jar");
      else if (args[1].endsWith("/uploads/.")) {
        mkdirSync(args[2], { recursive: true });
        writeFileSync(join(args[2], "photo.png"), "synthetic-photo");
      } else throw new Error("UNEXPECTED_COPY");
      return "";
    }
    if (args.includes("cat")) return readFileSync(config, "utf8");
    if (args.includes("wget")) return '{"content":[]}';
    if (args.some((v) => v.includes("exec pg_dump"))) {
      state.dumpCount++;
      if (state.throwDump) throw new Error("SECRET_NEVER_LOG");
      return Buffer.from("PGDMPsynthetic");
    }
    if (args.includes("pg_restore")) return "synthetic TOC";
    if (options.input?.includes("flyway_schema_history WHERE"))
      return JSON.stringify(rows);
    if (options.input?.includes("FROM public.matches WHERE"))
      return String(state.activeMatches);
    if (options.input?.includes("FROM pg_stat_activity"))
      return String(state.otherClients);
    if (options.input?.includes("FROM pg_tables"))
      return '["matches","undo_command_receipts"]';
    if (options.input?.includes("json_agg(r ORDER BY name)"))
      return '[{"name":"matches","count":"1","digest":"fixture-match"},{"name":"undo_command_receipts","count":"1","digest":"fixture-receipt"}]';
    throw new Error("UNEXPECTED_DOCKER_COMMAND");
  };
  const fetcher = async () => ({
    status: state.open ? 200 : 503,
    headers: new Headers({
      "X-UltiStats-Instance": instanceId,
      "X-UltiStats-Maintenance": transactionId,
    }),
    body: { cancel: async () => {} },
  });
  const call = (operation, extra = {}) =>
    executeRequest(
      {
        ...base,
        operation,
        ...(!["inspect"].includes(operation) ? { fencePath } : {}),
        ...(["backup", "postflight", "fence-release", "rollback-plan"].includes(
          operation,
        )
          ? { backupPath }
          : {}),
        ...extra,
      },
      { run, fetcher },
    );
  return {
    root,
    m,
    api,
    state,
    write,
    base,
    call,
    fencePath,
    backupPath,
    manifestPath,
    run,
    fetcher,
    record,
    recordPath,
  };
}
test("complete fenced backup target postflight release preserves data and private receipts", async (t) => {
  const f = fixture(t);
  f.api.State.Running = false;
  const acquired = await f.call("fence-acquire");
  assert.equal(acquired.evidence.quiet, true);
  const backed = await f.call("backup");
  assert.equal(backed.evidence.complete, true);
  assert.equal(f.state.dumpCount, 1);
  f.m.release.version = "0.1.0";
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
  f.m.release.version = "0.1.0";
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
