import { join, isAbsolute } from "node:path";
import { mkdtempSync, readFileSync, rmSync, realpathSync } from "node:fs";
import { tmpdir } from "node:os";
import {
  BASELINE,
  check,
  hash,
  safeReason,
  readPrivateJson,
  writePrivateJson,
  privateDirectory,
  validateManifest,
} from "./helper-contract.mjs";
import {
  command,
  sql,
  verifySourceIdentity,
  observeMigrations,
  parseCount,
  pair,
  databaseContent,
  databaseCatalog,
  observeInstallation,
  unknownClientFilter,
} from "./observe.mjs";
import { inspectBackupCapacity } from "./capacity.mjs";
import {
  createBackup,
  readBackup,
  uploadsFingerprint,
  settingsFingerprint,
} from "./backup.mjs";
import { acquireLegacyLease } from "./legacy-lease.mjs";
const WEB = "39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba";
const fullId = (x) => typeof x === "string" && /^[a-f0-9]{64}$/.test(x);
const imageId = (x) => typeof x === "string" && /^sha256:[a-f0-9]{64}$/.test(x);
function env(container) {
  return Object.fromEntries(
    (container.Config?.Env ?? []).map((v) => [
      v.slice(0, v.indexOf("=")),
      v.slice(v.indexOf("=") + 1),
    ]),
  );
}
function loopback(host) {
  return ["127.0.0.1", "::1", "[::1]"].includes(host);
}
export function verifyLegacyResources(
  { installation: m, claim: c, app, db, networks, volumes, containerIds },
  { quiet = false } = {},
) {
  check(
    c?.schemaVersion === 1 &&
      /^[a-z0-9][a-z0-9_-]{0,62}$/.test(c.project ?? "") &&
      isAbsolute(c.legacyRoot ?? "") &&
      c.webSource === WEB &&
      c.backendSource === BASELINE &&
      fullId(c.expectedJarSha256) &&
      /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(c.databaseName ?? "") &&
      /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(c.databaseUser ?? "") &&
      Array.isArray(c.settingsPaths ?? []),
    "LEGACY_SOURCE_UNSUPPORTED",
  );
  check(
    m?.schemaVersion === 1 &&
      m.root === c.legacyRoot &&
      m.mode === "stable" &&
      m.backend?.ownership === "managed" &&
      /^[a-f0-9]{12}$/.test(m.instanceId) &&
      m.release?.version === "unpublished" &&
      m.release.webSource === WEB &&
      m.release.backendSource === BASELINE &&
      m.backend.revision === BASELINE &&
      m.backend.source === join(c.legacyRoot, "dev/backend/.local/source") &&
      m.backend.project === c.project &&
      m.backend.origin === c.apiOrigin &&
      m.web?.origin === c.webOrigin &&
      m.legacy?.schemaVersion === 1 &&
      m.legacy.root === c.legacyRoot &&
      m.legacy.apiContainer === c.apiContainer &&
      m.legacy.dbContainer === c.dbContainer &&
      m.legacy.imageId === m.backend.imageId,
    "LEGACY_SOURCE_UNSUPPORTED",
  );
  check(
    fullId(c.apiContainer) &&
      fullId(c.dbContainer) &&
      c.apiContainer !== c.dbContainer &&
      app?.Id === c.apiContainer &&
      db?.Id === c.dbContainer &&
      imageId(app.Image) &&
      app.Image === m.backend.imageId &&
      imageId(db.Image) &&
      Array.isArray(containerIds) &&
      containerIds.length === 2 &&
      new Set(containerIds).size === 2 &&
      containerIds.includes(app.Id) &&
      containerIds.includes(db.Id),
    "LEGACY_OWNERSHIP_UNPROVEN",
  );
  for (const [container, role] of [
    [app, "api"],
    [db, "postgres"],
  ]) {
    const labels = container.Config?.Labels ?? {};
    check(
      labels["com.docker.compose.project"] === c.project &&
        labels["com.docker.compose.service"] === role &&
        labels["com.docker.compose.project.working_dir"] === c.legacyRoot,
      "LEGACY_OWNERSHIP_UNPROVEN",
    );
  }
  const databaseVolume = `${c.project}_database`,
    uploadsVolume = `${c.project}_uploads`;
  check(
    m.backend.databaseVolume === databaseVolume &&
      m.backend.uploadsVolume === uploadsVolume &&
      app.Mounts?.length === 1 &&
      db.Mounts?.length === 1,
    "LEGACY_STORAGE_UNPROVEN",
  );
  for (const [container, name, path] of [
    [app, uploadsVolume, "/app/uploads"],
    [db, databaseVolume, "/var/lib/postgresql/data"],
  ])
    check(
      container.Mounts[0].Type === "volume" &&
        container.Mounts[0].Name === name &&
        container.Mounts[0].Destination === path &&
        container.Mounts[0].RW === true,
      "LEGACY_STORAGE_UNPROVEN",
    );
  check(
    Array.isArray(volumes) &&
      volumes.length === 2 &&
      volumes.every(
        (v) =>
          [databaseVolume, uploadsVolume].includes(v.Name) &&
          typeof v.CreatedAt === "string" &&
          v.CreatedAt.length > 0 &&
          v.Driver === "local",
      ) &&
      new Set(volumes.map((v) => v.Name)).size === 2,
    "LEGACY_STORAGE_UNPROVEN",
  );
  const dbNetworks = Object.keys(db.NetworkSettings?.Networks ?? {}),
    appNetworks = Object.keys(app.NetworkSettings?.Networks ?? {});
  check(
    dbNetworks.length === 1 &&
      appNetworks.length === 1 &&
      dbNetworks[0] === appNetworks[0] &&
      networks?.length === 1 &&
      networks[0].Name === dbNetworks[0] &&
      Object.keys(networks[0].Containers ?? {}).every((id) =>
        [app.Id, db.Id].includes(id),
      ),
    "LEGACY_UNKNOWN_CLIENT",
  );
  check(
    db.NetworkSettings.Networks[dbNetworks[0]].Aliases?.includes("postgres"),
    "LEGACY_DATABASE_UNPROVEN",
  );
  const binds = db.HostConfig?.PortBindings ?? {};
  check(
    Object.keys(binds).every((port) => port === "5432/tcp") &&
      Object.values(binds)
        .flat()
        .every(
          (bind) =>
            bind && loopback(bind.HostIp) && /^[0-9]{1,5}$/.test(bind.HostPort),
        ),
    "LEGACY_DATABASE_EXPOSED",
  );
  const url = new URL(c.apiOrigin),
    apiBinds = app.HostConfig?.PortBindings?.["8080/tcp"];
  check(
    url.origin === c.apiOrigin &&
      url.protocol === "http:" &&
      (loopback(url.hostname) || url.hostname === "localhost") &&
      Object.keys(app.HostConfig.PortBindings).length === 1 &&
      apiBinds?.length === 1 &&
      loopback(apiBinds[0].HostIp) &&
      (url.hostname === "localhost" ||
        apiBinds[0].HostIp === url.hostname.replace(/^\[|\]$/g, "")) &&
      apiBinds[0].HostPort === (url.port || "80"),
    "LEGACY_OWNERSHIP_UNPROVEN",
  );
  const appEnv = env(app),
    dbEnv = env(db);
  check(
    app.Path === "java" &&
      JSON.stringify(app.Args) === JSON.stringify(["-jar", "app.jar"]) &&
      appEnv.DATABASE_URL ===
        `jdbc:postgresql://postgres:5432/${c.databaseName}` &&
      appEnv.DATABASE_USERNAME === c.databaseUser &&
      appEnv.APP_STORAGE_ROOT === "/app/uploads" &&
      dbEnv.POSTGRES_DB === c.databaseName &&
      dbEnv.POSTGRES_USER === c.databaseUser &&
      typeof dbEnv.POSTGRES_PASSWORD === "string" &&
      dbEnv.POSTGRES_PASSWORD.length > 0 &&
      appEnv.DATABASE_PASSWORD === dbEnv.POSTGRES_PASSWORD,
    "LEGACY_DATABASE_UNPROVEN",
  );
  check(db.State?.Running === true, "DATABASE_UNREADY");
  if (quiet) check(app.State?.Running === false, "LEGACY_WRITER_RUNNING");
  const manifest = structuredClone(m);
  manifest.backend.helper = {
    schemaVersion: 1,
    databaseContainerId: db.Id,
    writerContainerIds: [app.Id],
    databaseName: c.databaseName,
    databaseUser: c.databaseUser,
    databaseNetwork: dbNetworks[0],
    uploadsMount: "/app/uploads",
    databaseMount: "/var/lib/postgresql/data",
    settingsPaths: c.settingsPaths ?? [],
    productBaseline: BASELINE,
    migrationFingerprint: null,
    jarSha256: c.expectedJarSha256,
    sourceCheckout: m.backend.source,
  };
  const identity = {
    instanceId: m.instanceId,
    project: c.project,
    apiContainerId: app.Id,
    databaseContainerId: db.Id,
    databaseImageId: db.Image,
    originalDatabaseImageId: db.Image,
    databaseVolume,
    uploadsVolume,
    origin: c.apiOrigin,
    webOrigin: c.webOrigin,
    storage: volumes
      .map((v) => ({ name: v.Name, createdAt: v.CreatedAt, driver: v.Driver }))
      .sort((a, b) => a.name.localeCompare(b.name)),
  };
  return {
    manifest,
    identity,
    databaseNetwork: dbNetworks[0],
    appAddress: app.NetworkSettings.Networks[appNetworks[0]].IPAddress,
    appAddresses: [
      app.NetworkSettings.Networks[appNetworks[0]].IPAddress,
      app.NetworkSettings.Networks[appNetworks[0]].GlobalIPv6Address,
    ].filter(Boolean),
  };
}
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function controlBinding(installation, { run = command } = {}) {
  const t = installation.tooling;
  check(
    t &&
      Object.keys(t).length === 4 &&
      ["webRoot", "backendRoot"].every(
        (k) => isAbsolute(t[k] ?? "") && realpathSync(t[k]) === t[k],
      ) &&
      ["webSource", "backendSource"].every((k) =>
        /^[a-f0-9]{40}$/.test(t[k] ?? ""),
      ) &&
      t.webRoot !== installation.root &&
      t.backendRoot !== installation.backend.source,
    "LEGACY_CONTROL_UNPROVEN",
  );
  const canonical = {
    webRoot: t.webRoot,
    webSource: t.webSource,
    backendRoot: t.backendRoot,
    backendSource: t.backendSource,
  };
  for (const role of ["web", "backend"]) {
    const git = (...args) =>
      run("git", [
        "--no-replace-objects",
        "--no-optional-locks",
        "-c",
        "core.fsmonitor=false",
        "-c",
        "core.hooksPath=/dev/null",
        "-C",
        t[`${role}Root`],
        ...args,
      ]).trim();
    check(
      git("rev-parse", "HEAD") === t[`${role}Source`] &&
        git("rev-parse", "--show-toplevel") === t[`${role}Root`],
      "LEGACY_CONTROL_UNPROVEN",
    );
  }
  // The Web caller additionally verifies full raw controller bytes before import and again before opening ingress.
  return {
    controlSources: { web: t.webSource, backend: t.backendSource },
    controlSha256: hash(JSON.stringify(canonical)),
  };
}
async function observeLegacy(
  installation,
  claim,
  { run = command, fetcher = fetch, quiet = false, leasePid } = {},
) {
  check(
    /^[a-z0-9][a-z0-9_-]{0,62}$/.test(claim?.project ?? "") &&
      fullId(claim.apiContainer) &&
      fullId(claim.dbContainer),
    "LEGACY_OWNERSHIP_UNPROVEN",
  );
  const docker = (...args) => run("docker", args).trim();
  const inspect = (kind, id) => {
    const rows = JSON.parse(
      docker(
        ...(kind === "container" ? ["inspect", id] : [kind, "inspect", id]),
      ),
    );
    check(rows.length === 1, "LEGACY_OWNERSHIP_UNPROVEN");
    return rows[0];
  };
  const app = inspect("container", claim.apiContainer),
    db = inspect("container", claim.dbContainer);
  const networks = Object.keys(db.NetworkSettings?.Networks ?? {}).map((n) =>
    inspect("network", n),
  );
  const volumes = [
    installation.backend.databaseVolume,
    installation.backend.uploadsVolume,
  ].map((n) => inspect("volume", n));
  const containerIds = docker(
    "ps",
    "--all",
    "--no-trunc",
    "--filter",
    `label=com.docker.compose.project=${claim.project}`,
    "--format",
    "{{.ID}}",
  )
    .split("\n")
    .filter(Boolean);
  const proof = verifyLegacyResources(
    { installation, claim, app, db, networks, volumes, containerIds },
    { quiet },
  );
  const m = proof.manifest,
    h = m.backend.helper;
  const control = controlBinding(m, { run });
  // The owner registry is derived from the actual old Web checkout, never an arbitrary claim path.
  const gitDir = run("git", [
    "--no-replace-objects",
    "--no-optional-locks",
    "-c",
    "core.fsmonitor=false",
    "-c",
    "core.hooksPath=/dev/null",
    "-C",
    claim.legacyRoot,
    "rev-parse",
    "--absolute-git-dir",
  ]).trim();
  const ownerPath = join(gitDir, "ultistats/stable-owner.json");
  check(installation.legacy.ownerPath === ownerPath, "LEGACY_OWNER_UNPROVEN");
  const owner = readPrivateJson(ownerPath);
  check(
    owner.instanceId === m.instanceId &&
      owner.installationRoot === m.installationRoot &&
      owner.apiContainer === claim.apiContainer &&
      owner.dbContainer === claim.dbContainer,
    "LEGACY_OWNER_UNPROVEN",
  );
  const sourceProof = verifySourceIdentity(m, { run });
  const temp = mkdtempSync(join(tmpdir(), "ultistats-legacy-jar-"));
  let jarSha256;
  try {
    docker("cp", `${app.Id}:/app/app.jar`, join(temp, "app.jar"));
    jarSha256 = hash(readFileSync(join(temp, "app.jar")));
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
  check(jarSha256 === claim.expectedJarSha256, "RUNTIME_UNPROVEN");
  check(
    sql(m, "SELECT current_setting('server_version_num')::int / 10000;", {
      run,
    }) === "16",
    "LEGACY_DATABASE_UNPROVEN",
  );
  const migrations = observeMigrations(m, { run, bootstrap: true });
  check(migrations.migrationsVerified, "MIGRATION_UNPROVEN");
  h.migrationFingerprint = migrations.migrationFingerprint;
  const activeMatches = parseCount(
    sql(
      m,
      "SELECT count(*) FROM public.matches WHERE started_at IS NOT NULL AND ended_at IS NULL;",
      { run },
    ),
  );
  check(activeMatches === 0, "MATCH_IN_PROGRESS");
  const allowedIPs = quiet ? [] : proof.appAddresses;
  check(quiet || allowedIPs.length > 0, "LEGACY_UNKNOWN_CLIENT");
  if (leasePid !== undefined)
    check(Number.isSafeInteger(leasePid) && leasePid > 0, "LEGACY_FENCE_LOST");
  const allowed = unknownClientFilter(h.databaseUser, allowedIPs);
  const clients = parseCount(
    sql(
      m,
      `SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND datname=current_database() AND pid<>pg_backend_pid() ${leasePid ? `AND pid<>${leasePid}` : ""} ${allowed};`,
      { run },
    ),
  );
  check(clients === 0, "UNKNOWN_DATABASE_CLIENT");
  if (!quiet) {
    check(app.State.Running === true, "RUNTIME_UNPROVEN");
    const response = await fetcher(
      `${m.backend.origin}/api/v1/matches?size=1`,
      { signal: AbortSignal.timeout(5000), redirect: "error" },
    );
    check(response.status === 200, "SPORTING_API_UNREADY");
    await response.body?.cancel();
  }
  return {
    manifest: m,
    controlBinding: control,
    catalogSha256: databaseCatalog(m, { run }),
    sourceProof,
    identity: proof.identity,
    schema: 9,
    activeMatches,
    compatibility: {
      verified: true,
      productBaseline: BASELINE,
      migrationFingerprint: migrations.migrationFingerprint,
    },
    current: pair(m),
    runtime: { imageId: app.Image, jarSha256, sourceSha: BASELINE },
    ready: true,
  };
}
function publicEvidence(observation) {
  const { manifest, controlBinding, ...evidence } = observation;
  return evidence;
}
const safeMethod =
  (method) =>
  async (...args) => {
    try {
      return await method(...args);
    } catch (error) {
      throw new Error(safeReason(error));
    }
  };
async function legacyOperation(operation, transactionId, action) {
  try {
    const value = await action();
    return {
      protocolVersion: 1,
      operation,
      ...(transactionId ? { transactionId } : {}),
      disposition: "ok",
      reason: "VERIFIED",
      ...value,
    };
  } catch (error) {
    return {
      protocolVersion: 1,
      operation,
      ...(transactionId ? { transactionId } : {}),
      disposition:
        operation === "legacy-inspect" ? "blocked" : "maintenance_required",
      reason: safeReason(error),
      evidence: {},
    };
  }
}
export async function inspectLegacyAdoption(
  { installation, claim, backupPath },
  options = {},
) {
  return legacyOperation("legacy-inspect", undefined, async () => {
    const observed = await observeLegacy(installation, claim, options);
    const run = options.run ?? command;
    const capacity = inspectBackupCapacity(observed.manifest, backupPath, {
      run,
    });
    return {
      evidence: {
        ...publicEvidence(observed),
        capacity,
        dataFingerprint: databaseContent(observed.manifest, { run }),
      },
    };
  });
}
export async function backupLegacyAdoption(
  { installation, claim, backupPath, evidenceDirectory, transactionId },
  options = {},
) {
  return legacyOperation("legacy-backup", transactionId, async () => {
    check(
      /^[A-Za-z0-9-]{8,80}$/.test(transactionId ?? ""),
      "INVALID_LEGACY_LEASE",
    );
    privateDirectory(evidenceDirectory);
    const run = options.run ?? command;
    const initial = await observeLegacy(installation, claim, {
      ...options,
      quiet: true,
    });
    let lease;
    try {
      lease = await acquireLegacyLease(initial.manifest, {
        transactionId,
        run,
        spawnProcess: options.spawnProcess,
      });
      const receipt = {
        protocolVersion: 1,
        operation: "legacy-fence",
        transactionId,
        claimSha256: hash(JSON.stringify(claim)),
        installationSha256: hash(JSON.stringify(installation)),
        current: initial.current,
        identity: initial.identity,
        runtime: initial.runtime,
        sourceProof: initial.sourceProof,
        migrationFingerprint: initial.compatibility.migrationFingerprint,
        catalogSha256: initial.catalogSha256,
        controlBinding: initial.controlBinding,
        manifest: initial.manifest,
        leaseId: lease.id,
      };
      const recheck = async () => {
        await lease.recheck();
        const actual = await observeLegacy(installation, claim, {
          ...options,
          quiet: true,
          leasePid: lease.backendPid,
        });
        check(
          same(actual.identity, initial.identity) &&
            same(actual.runtime, initial.runtime) &&
            same(actual.sourceProof, initial.sourceProof) &&
            actual.compatibility.migrationFingerprint ===
              receipt.migrationFingerprint &&
            actual.catalogSha256 === receipt.catalogSha256 &&
            same(actual.controlBinding, receipt.controlBinding),
          "LEGACY_IDENTITY_CHANGED",
        );
      };
      const complete = await createBackup({
        backupPath,
        manifest: initial.manifest,
        receipt,
        recheck,
        run,
      });
      await recheck();
      const manifestPath = join(backupPath, "manifest.json"),
        manifestSha256 = hash(readFileSync(manifestPath));
      const handle = {
        id: lease.id,
        recheck: safeMethod(recheck),
        release: safeMethod(lease.release),
        confirmDatabaseStopped: safeMethod(async () => {
          const stopped = await lease.confirmDatabaseStopped();
          check(
            stopped.databaseImageId === initial.identity.databaseImageId,
            "LEGACY_IDENTITY_CHANGED",
          );
          const stoppedPath = join(backupPath, "legacy-database-stopped.json");
          writePrivateJson(stoppedPath, {
            protocolVersion: 1,
            operation: "legacy-database-stopped",
            transactionId,
            manifestSha256,
            identity: initial.identity,
            leaseId: lease.id,
            stopped: true,
          });
          return { ...stopped, stoppedPath };
        }),
        toJSON() {
          throw new Error("LEGACY_FENCE_HANDLE_NOT_SERIALIZABLE");
        },
      };
      return {
        evidence: {
          ...publicEvidence(initial),
          capacity: complete.capacity,
          backupPath,
          manifestPath,
          manifestSha256,
          complete: true,
        },
        lease: handle,
      };
    } catch (error) {
      await lease?.release();
      throw error;
    }
  });
}
async function observeLegacyTransition(
  { installation, backupPath, transactionId, evidenceDirectory },
  options = {},
) {
  check(
    /^[A-Za-z0-9-]{8,80}$/.test(transactionId ?? ""),
    "INVALID_LEGACY_LEASE",
  );
  privateDirectory(evidenceDirectory);
  const m = validateManifest(installation),
    run = options.run ?? command;
  check(!m.legacy, "LEGACY_TRANSITION_UNPROVEN");
  const backup = readBackup(backupPath),
    fence = backup.fence,
    manifestSha256 = hash(readFileSync(join(backupPath, "manifest.json"))),
    stopped = readPrivateJson(join(backupPath, "legacy-database-stopped.json"));
  check(
    fence?.protocolVersion === 1 &&
      fence.operation === "legacy-fence" &&
      fence.transactionId === transactionId &&
      same(fence.current, pair(m)) &&
      fence.current.web === WEB &&
      fence.current.backend === BASELINE &&
      stopped.protocolVersion === 1 &&
      stopped.operation === "legacy-database-stopped" &&
      stopped.transactionId === transactionId &&
      stopped.manifestSha256 === manifestSha256 &&
      stopped.stopped === true &&
      stopped.leaseId === fence.leaseId &&
      same(stopped.identity, fence.identity),
    "LEGACY_TRANSITION_UNPROVEN",
  );
  check(
    m.instanceId === fence.identity.instanceId &&
      m.backend.origin === fence.identity.origin &&
      m.web?.origin === fence.identity.webOrigin &&
      m.backend.databaseVolume === fence.identity.databaseVolume &&
      m.backend.uploadsVolume === fence.identity.uploadsVolume &&
      m.backend.helper.databaseContainerId !==
        fence.identity.databaseContainerId,
    "LEGACY_IDENTITY_CHANGED",
  );
  const control = controlBinding(m, { run });
  check(same(control, fence.controlBinding), "LEGACY_CONTROL_CHANGED");
  const inspect = (id) => {
    const rows = JSON.parse(run("docker", ["inspect", id]));
    check(rows.length === 1 && rows[0].Id === id, "LEGACY_IDENTITY_CHANGED");
    return rows[0];
  };
  const oldDB = inspect(fence.identity.databaseContainerId);
  check(
    oldDB.State?.Running === false &&
      oldDB.Image === fence.identity.databaseImageId,
    "LEGACY_DATABASE_STILL_RUNNING",
  );
  const newDB = inspect(m.backend.helper.databaseContainerId);
  check(
    newDB.Image === fence.identity.databaseImageId,
    "LEGACY_DATABASE_IMAGE_CHANGED",
  );
  const storage = fence.identity.storage.map((v) => {
    const actual = JSON.parse(run("docker", ["volume", "inspect", v.name]))[0];
    return {
      name: actual.Name,
      createdAt: actual.CreatedAt,
      driver: actual.Driver,
    };
  });
  check(same(storage, fence.identity.storage), "LEGACY_STORAGE_UNPROVEN");
  const observed = await observeInstallation(m, {
    ...options,
    run,
    phase: "target",
    transactionId,
  });
  check(
    same(
      {
        imageId: m.backend.imageId,
        jarSha256: observed.jarSha256,
        sourceSha: m.backend.revision,
      },
      fence.runtime,
    ) && observed.migrationFingerprint === fence.migrationFingerprint,
    "LEGACY_IDENTITY_CHANGED",
  );
  check(
    databaseContent(m, { run }) === backup.databaseContentSha256 &&
      uploadsFingerprint(m, { run }) === backup.uploadsSha256 &&
      same(settingsFingerprint(m), backup.settings),
    "DATA_PRESERVATION_FAILED",
  );
  check(
    databaseCatalog(m, { run }) === fence.catalogSha256,
    "DATABASE_CATALOG_CHANGED",
  );
  const body = run(
    "docker",
    [
      "exec",
      m.backend.helper.databaseContainerId,
      "wget",
      "-q",
      "-T",
      "5",
      "-O",
      "-",
      "http://api:8080/api/v1/matches?size=1",
    ],
    { timeout: 10000, maxBuffer: 1024 * 1024 },
  );
  JSON.parse(body);
  const transitionPath = join(
    evidenceDirectory,
    `${transactionId}.legacy-postflight.json`,
  );
  const evidence = {
    schema: 9,
    activeMatches: observed.activeMatches,
    current: pair(m),
    identity: {
      ...fence.identity,
      project: m.backend.project,
      databaseContainerId: m.backend.helper.databaseContainerId,
      apiContainerId: m.backend.helper.writerContainerIds[0],
    },
    runtime: fence.runtime,
    sourceProof: observed.sourceProof,
    dataProof: {
      databaseContentSha256: backup.databaseContentSha256,
      catalogSha256: fence.catalogSha256,
      uploadsSha256: backup.uploadsSha256,
      settingsSha256: hash(JSON.stringify(backup.settings)),
      migrationFingerprint: observed.migrationFingerprint,
    },
    verified: true,
    preserved: true,
    backupPath,
    manifestPath: join(backupPath, "manifest.json"),
    manifestSha256,
    transitionPath,
  };
  return {
    evidence,
    installationSha256: hash(JSON.stringify(m)),
    claimSha256: fence.claimSha256,
    backupManifestSha256: manifestSha256,
    ...control,
  };
}
export async function verifyLegacyTransition(args, options = {}) {
  return legacyOperation("legacy-postflight", args.transactionId, async () => {
    const proof = await observeLegacyTransition(args, options);
    writePrivateJson(proof.evidence.transitionPath, {
      protocolVersion: 1,
      operation: "legacy-postflight",
      transactionId: args.transactionId,
      disposition: "ok",
      reason: "VERIFIED",
      ...proof,
    });
    return { evidence: proof.evidence };
  });
}
export async function completeLegacyAdoption(args, options = {}) {
  return legacyOperation("legacy-release", args.transactionId, async () => {
    const proof = await observeLegacyTransition(args, options),
      postflightPath = proof.evidence.transitionPath,
      postflight = readPrivateJson(postflightPath);
    check(
      postflight.protocolVersion === 1 &&
        postflight.operation === "legacy-postflight" &&
        postflight.transactionId === args.transactionId &&
        postflight.disposition === "ok" &&
        same(postflight.evidence, proof.evidence) &&
        postflight.installationSha256 === proof.installationSha256 &&
        postflight.claimSha256 === proof.claimSha256 &&
        postflight.backupManifestSha256 === proof.backupManifestSha256 &&
        same(postflight.controlSources, proof.controlSources) &&
        postflight.controlSha256 === proof.controlSha256,
      "LEGACY_POSTFLIGHT_MISMATCH",
    );
    const releasePath = join(
      args.evidenceDirectory,
      `${args.transactionId}.legacy-released.json`,
    );
    const evidence = {
      ...proof.evidence,
      released: true,
      releasePath,
      postflightPath,
      postflightSha256: hash(readFileSync(postflightPath)),
      claimSha256: proof.claimSha256,
      backupManifestSha256: proof.backupManifestSha256,
      installationSha256: proof.installationSha256,
      controlSources: proof.controlSources,
      controlSha256: proof.controlSha256,
    };
    writePrivateJson(releasePath, {
      protocolVersion: 1,
      operation: "legacy-release",
      transactionId: args.transactionId,
      disposition: "ok",
      reason: "VERIFIED",
      evidence,
    });
    return { evidence };
  });
}
