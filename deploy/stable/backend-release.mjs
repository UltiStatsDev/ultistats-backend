import { readFileSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { pathToFileURL } from "node:url";
import {
  BASELINE,
  check,
  hash,
  readPrivateJson,
  writePrivateJson,
  privateDirectory,
  validateRequest,
  validateManifest,
  safeReason,
} from "./helper-contract.mjs";
import {
  observeInstallation,
  verifySourceIdentity,
  fixedIdentity,
  pair,
  databaseContent,
  command,
} from "./observe.mjs";
import {
  createBackup,
  readBackup,
  uploadsFingerprint,
  settingsFingerprint,
} from "./backup.mjs";
import { rollbackCompatible } from "./maintenance.mjs";
import { inspectBackupCapacity } from "./capacity.mjs";
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export function verifyFence(
  f,
  r,
  manifestSha256,
  identity,
  { target = false } = {},
) {
  check(
    f?.protocolVersion === 1 &&
      f.transactionId === r.transactionId &&
      f.targetRecordSha256 === r.targetRecordSha256 &&
      same(f.identity, identity) &&
      (target || f.manifestSha256 === manifestSha256),
    "FENCE_MISMATCH",
  );
  return true;
}
export function desiredPair(record, rollbackPlan) {
  if (rollbackPlan) {
    check(
      rollbackPlan.compatible === true && rollbackPlan.previous,
      "ROLLBACK_UNPROVEN",
    );
    return rollbackPlan.previous;
  }
  return {
    version: `v${record.version}`,
    web: record.repositories.web.commit,
    backend: record.repositories.backend.commit,
  };
}
function targetRecord(path, digest) {
  const r = readPrivateJson(path);
  check(hash(readFileSync(path)) === digest, "RECORD_HASH_MISMATCH");
  check(
    r.schemaVersion === 1 &&
      r.status === "finalized" &&
      /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(r.version),
    "INVALID_RECORD",
  );
  for (const component of ["web", "backend"]) {
    const repo = r.repositories?.[component];
    check(
      repo?.repository ===
        `UltiStatsDev/ultistats-${component === "web" ? "web-ui" : "backend"}` &&
        /^[a-f0-9]{40}$/.test(repo.commit) &&
        repo.tag === `v${r.version}`,
      "INVALID_RECORD",
    );
  }
  check(
    r.backendPin === r.repositories.backend.commit &&
      r.compatibility?.fromSchema === 9 &&
      r.compatibility.toSchema === 9 &&
      Array.isArray(r.compatibility.supportedSourcePairs) &&
      r.compatibility.supportedSourcePairs.length > 0 &&
      r.compatibility.supportedSourcePairs.every(
        (p) => /^[a-f0-9]{40}$/.test(p.web) && /^[a-f0-9]{40}$/.test(p.backend),
      ),
    "COMPATIBILITY_UNPROVEN",
  );
  return r;
}
function receiptPath(r, kind) {
  return join(
    r.evidenceDirectory,
    `${r.transactionId}.${r.rollbackPlanPath ? "rollback-" : ""}${kind}.json`,
  );
}
function evidence(m, record, o) {
  return {
    schema: 9,
    activeMatches: o.activeMatches,
    current: pair(m),
    target: desiredPair(record),
    identity: fixedIdentity(m),
    compatibility: {
      verified: true,
      productBaseline: BASELINE,
      migrationFingerprint: o.migrationFingerprint,
    },
    sourceProof: o.sourceProof,
  };
}
function runtime(m, o) {
  return {
    imageId: m.backend.imageId,
    jarSha256: o.jarSha256,
    sourceSha: m.backend.revision,
  };
}
function assertData(m, backup, { run }) {
  check(
    databaseContent(m, { run }) === backup.databaseContentSha256 &&
      uploadsFingerprint(m, { run }) === backup.uploadsSha256 &&
      same(settingsFingerprint(m), backup.settings),
    "DATA_PRESERVATION_FAILED",
  );
}
function boundBackup(r, m, manifestHash, { target = false } = {}) {
  const backup = readBackup(r.backupPath);
  const fence = readPrivateJson(r.fencePath);
  check(same(backup.fence, fence), "FENCE_MISMATCH");
  verifyFence(fence, r, manifestHash, fixedIdentity(m), { target });
  check(backup.transactionId === r.transactionId, "BACKUP_MISMATCH");
  return {
    backup,
    fence,
    backupHash: hash(readFileSync(join(r.backupPath, "manifest.json"))),
  };
}
function boundRollback(r, manifestHash, identity, backupHash) {
  if (!r.rollbackPlanPath) return undefined;
  const planner = readPrivateJson(r.rollbackPlanPath);
  check(
    planner.protocolVersion === 1 &&
      planner.operation === "rollback-plan" &&
      planner.transactionId === r.transactionId &&
      planner.targetRecordSha256 === r.targetRecordSha256 &&
      planner.backupHash === backupHash &&
      same(planner.identity, identity) &&
      planner.compatible === true,
    "ROLLBACK_UNPROVEN",
  );
  // manifestHash is deliberately target/previous runtime specific and checked by postflight receipt.
  return planner;
}

export async function executeRequest(
  request,
  { run = command, fetcher = fetch } = {},
) {
  const r = validateRequest(request);
  privateDirectory(r.evidenceDirectory);
  const m = validateManifest(readPrivateJson(r.installationManifestPath), {
    bootstrap: r.operation === "inspect",
  });
  const manifestHash = hash(readFileSync(r.installationManifestPath));
  const record = targetRecord(r.targetRecordPath, r.targetRecordSha256);
  const options = { run, fetcher, transactionId: r.transactionId };
  const observe = (phase) =>
    observeInstallation(m, {
      ...options,
      phase,
      bootstrap: r.operation === "inspect",
    });
  const success = (ev) => ({
    protocolVersion: 1,
    operation: r.operation,
    transactionId: r.transactionId,
    disposition: "ok",
    reason: "VERIFIED",
    evidence: ev,
  });
  const knownPair = (p) =>
    record.compatibility.supportedSourcePairs.some(
      (s) => s.web === p.web && s.backend === p.backend,
    );
  if (r.operation === "inspect")
    check(
      knownPair(pair(m)) || same(pair(m), desiredPair(record)),
      "SOURCE_PAIR_UNSUPPORTED",
    );
  if (["fence-acquire", "fence-recheck", "backup"].includes(r.operation))
    check(knownPair(pair(m)), "SOURCE_PAIR_UNSUPPORTED");
  if (r.operation === "inspect") {
    const o = await observe("inspect");
    const response = await fetcher(
      `${m.backend.origin}/api/v1/matches?size=1`,
      { signal: AbortSignal.timeout(5000), redirect: "error" },
    );
    check(response.status === 200, "SPORTING_API_UNREADY");
    await response.body?.cancel();
    return success({
      ...evidence(m, record, o),
      capacity: inspectBackupCapacity(
        m,
        r.backupPath ?? join(r.evidenceDirectory, "backup"),
        { run },
      ),
      runtime: runtime(m, o),
      ready: true,
    });
  }
  if (r.operation === "fence-acquire") {
    const o = await observe("quiet");
    const fence = {
      protocolVersion: 1,
      transactionId: r.transactionId,
      targetRecordSha256: r.targetRecordSha256,
      manifestSha256: manifestHash,
      identity: fixedIdentity(m),
      current: pair(m),
      runtime: runtime(m, o),
    };
    writePrivateJson(r.fencePath, fence);
    return success({
      ...evidence(m, record, o),
      fencePath: r.fencePath,
      fenceSha256: hash(readFileSync(r.fencePath)),
      quiet: true,
    });
  }
  if (r.operation === "fence-recheck" || r.operation === "backup") {
    const fence = readPrivateJson(r.fencePath);
    verifyFence(fence, r, manifestHash, fixedIdentity(m));
    const o = await observe("quiet");
    if (r.operation === "fence-recheck")
      return success({
        ...evidence(m, record, o),
        fencePath: r.fencePath,
        fenceSha256: hash(readFileSync(r.fencePath)),
        quiet: true,
      });
    const completedBackup = await createBackup({
      backupPath: r.backupPath,
      manifest: m,
      receipt: fence,
      run,
      recheck: async () => {
        verifyFence(
          readPrivateJson(r.fencePath),
          r,
          manifestHash,
          fixedIdentity(m),
        );
        await observe("quiet");
      },
    });
    const manifestPath = join(r.backupPath, "manifest.json");
    return success({
      ...evidence(m, record, o),
      backupPath: r.backupPath,
      manifestPath,
      manifestSha256: hash(readFileSync(manifestPath)),
      complete: true,
      capacity: completedBackup.capacity,
    });
  }
  const { backup, fence, backupHash } = boundBackup(r, m, manifestHash, {
    target: true,
  });
  if (r.operation === "rollback-plan") {
    const o = await observe("quiet");
    const previous = validateManifest(readPrivateJson(r.previousManifestPath));
    check(
      hash(readFileSync(r.previousManifestPath)) === fence.manifestSha256 &&
        same(fixedIdentity(previous), fixedIdentity(m)) &&
        same(pair(previous), fence.current),
      "ROLLBACK_UNPROVEN",
    );
    check(
      rollbackCompatible(
        pair(previous),
        record.compatibility,
        o.migrationsVerified && o.sourceVerified,
      ),
      "ROLLBACK_UNPROVEN",
    );
    // Prove the previous binary/source independently, not only the current failed target.
    verifySourceIdentity(previous, { run });
    check(
      same(fence.runtime, {
        imageId: previous.backend.imageId,
        jarSha256: previous.backend.helper.jarSha256,
        sourceSha: previous.backend.revision,
      }),
      "ROLLBACK_UNPROVEN",
    );
    assertData(m, backup, { run });
    const rollbackPlanPath = join(
      r.evidenceDirectory,
      `${r.transactionId}.rollback-plan.json`,
    );
    const plan = {
      protocolVersion: 1,
      operation: "rollback-plan",
      transactionId: r.transactionId,
      targetRecordSha256: r.targetRecordSha256,
      backupHash,
      identity: fixedIdentity(m),
      compatible: true,
      previous: pair(previous),
    };
    writePrivateJson(rollbackPlanPath, plan);
    return success({
      ...evidence(m, record, o),
      compatible: true,
      previous: pair(previous),
      rollbackPlanPath,
    });
  }
  const planner = boundRollback(r, manifestHash, fixedIdentity(m), backupHash);
  if (planner)
    check(
      same(planner.previous, fence.current) &&
        rollbackCompatible(planner.previous, record.compatibility, true),
      "ROLLBACK_UNPROVEN",
    );
  check(same(pair(m), desiredPair(record, planner)), "TARGET_PAIR_MISMATCH");
  const o = await observe("target");
  if (planner) check(same(runtime(m, o), fence.runtime), "ROLLBACK_UNPROVEN");
  assertData(m, backup, { run });
  // Internal sporting HTTP proof goes only to the owned API; public ingress stays503.
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
  check(
    body.trim().startsWith("{") || body.trim().startsWith("["),
    "SPORTING_API_UNREADY",
  );
  JSON.parse(body);
  const postflightPath = receiptPath(r, "postflight");
  const proof = {
    protocolVersion: 1,
    operation: "postflight",
    transactionId: r.transactionId,
    targetRecordSha256: r.targetRecordSha256,
    manifestSha256: manifestHash,
    backupHash,
    rollbackPlanSha256: r.rollbackPlanPath
      ? hash(readFileSync(r.rollbackPlanPath))
      : null,
    identity: fixedIdentity(m),
    current: pair(m),
    verified: true,
  };
  if (r.operation === "postflight") {
    writePrivateJson(postflightPath, proof);
    return success({
      ...evidence(m, record, o),
      verified: true,
      preserved: true,
      runtime: runtime(m, o),
      postflightPath,
    });
  }
  check(
    r.operation === "fence-release" &&
      same(readPrivateJson(postflightPath), proof),
    "POSTFLIGHT_MISMATCH",
  );
  const releasePath = receiptPath(r, "released");
  const result = success({
    ...evidence(m, record, o),
    released: true,
    releasePath,
  });
  writePrivateJson(releasePath, result);
  return result;
}

export async function main(argv = process.argv.slice(2)) {
  let request,
    resultPath,
    validated = false;
  try {
    check(
      argv.length === 5 && argv[1] === "--request" && argv[3] === "--result",
      "USAGE",
    );
    resultPath = resolve(argv[4]);
    check(argv[4] === resultPath && resultPath !== argv[2], "USAGE");
    privateDirectory(dirname(resultPath));
    request = readPrivateJson(argv[2]);
    validateRequest(request);
    check(request.operation === argv[0], "INVALID_REQUEST");
    const paths = [
      argv[2],
      request.installationManifestPath,
      request.targetRecordPath,
      request.fencePath,
      request.previousManifestPath,
      request.rollbackPlanPath,
    ].filter(Boolean);
    check(!paths.includes(resultPath), "USAGE");
    validated = true;
    const result = await executeRequest(request);
    writePrivateJson(resultPath, result);
    return 0;
  } catch (error) {
    const reason = safeReason(error);
    if (validated && resultPath) {
      const maintenance = ![
        "inspect",
        "fence-acquire",
        "fence-recheck",
      ].includes(request.operation);
      const disposition = maintenance ? "maintenance_required" : "blocked";
      const result = {
        protocolVersion: 1,
        operation: request.operation,
        transactionId: request.transactionId,
        disposition,
        reason,
        evidence: {},
      };
      try {
        writePrivateJson(resultPath, result);
      } catch {}
      return reason === "HELPER_FAILED" ? 70 : maintenance ? 3 : 2;
    }
    process.stderr.write(
      `${reason === "HELPER_FAILED" ? "HELPER_FAILED" : reason}\n`,
    );
    return ["USAGE", "INVALID_REQUEST"].includes(reason) ? 64 : 70;
  }
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
)
  process.exitCode = await main();
