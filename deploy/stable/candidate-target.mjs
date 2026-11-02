import { readFileSync, lstatSync, realpathSync, mkdtempSync, rmSync } from "node:fs";
import { join, resolve, isAbsolute } from "node:path";
import { tmpdir } from "node:os";
import { isDeepStrictEqual as same } from "node:util";
import { check, hash, OPERATIONS, readPrivateJson, privateDirectory } from "./helper-contract.mjs";
import { inspectSource } from "./build.mjs";
import { command, fixedIdentity, pair } from "./observe.mjs";
const KIND = "owned-unpublished-candidate";
const sha = (x) => typeof x === "string" && /^[a-f0-9]{40}$/.test(x);
const digest = (x) => typeof x === "string" && /^[a-f0-9]{64}$/.test(x);
const image = (x) => typeof x === "string" && /^sha256:[a-f0-9]{64}$/.test(x);
const path = (x) => typeof x === "string" && isAbsolute(x) && resolve(x) === x;
function exact(value, keys, optional = []) {
  check(
    value &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      keys.every((k) => Object.hasOwn(value, k)) &&
      Object.keys(value).every((k) => [...keys, ...optional].includes(k)),
    "INVALID_CANDIDATE",
  );
}
function candidatePair(p) {
  exact(p, ["version", "web", "backend"]);
  check(p.version === "unpublished" && sha(p.web) && sha(p.backend), "INVALID_CANDIDATE");
}
export function validateCandidateDescriptor(d) {
  exact(d, [
    "schemaVersion",
    "kind",
    "transactionId",
    "installationIdentity",
    "sourceManifestSha256",
    "current",
    "target",
    "compatibility",
    "artifacts",
  ]);
  check(
    d.schemaVersion === 1 &&
      d.kind === KIND &&
      /^[A-Za-z0-9-]{8,80}$/.test(d.transactionId) &&
      digest(d.sourceManifestSha256),
    "INVALID_CANDIDATE",
  );
  const i = d.installationIdentity;
  exact(i, ["instanceId", "databaseContainerId", "databaseVolume", "uploadsVolume", "origin"]);
  check(
    /^[a-f0-9]{12}$/.test(i.instanceId) &&
      digest(i.databaseContainerId) &&
      typeof i.databaseVolume === "string" &&
      typeof i.uploadsVolume === "string" &&
      i.databaseVolume !== i.uploadsVolume &&
      typeof i.origin === "string",
    "INVALID_CANDIDATE",
  );
  candidatePair(d.current);
  candidatePair(d.target);
  const c = d.compatibility;
  exact(c, ["fromSchema", "toSchema", "appRollback", "supportedSourcePairs"]);
  check(
    c.fromSchema === 9 &&
      c.toSchema === 9 &&
      c.appRollback === true &&
      Array.isArray(c.supportedSourcePairs) &&
      c.supportedSourcePairs.length > 0,
    "INVALID_CANDIDATE",
  );
  for (const p of c.supportedSourcePairs) {
    exact(p, ["web", "backend"]);
    check(sha(p.web) && sha(p.backend), "INVALID_CANDIDATE");
  }
  check(
    new Set(c.supportedSourcePairs.map((p) => `${p.web}:${p.backend}`)).size ===
      c.supportedSourcePairs.length &&
      [d.current, d.target].every((p) =>
        c.supportedSourcePairs.some((s) => s.web === p.web && s.backend === p.backend),
      ),
    "INVALID_CANDIDATE",
  );
  exact(d.artifacts, ["current", "target"]);
  for (const phase of ["current", "target"]) {
    const f = d.artifacts[phase];
    exact(f, ["stageSha256", "backend", "web"]);
    exact(f.backend, [
      "sourceSha",
      "sourceTree",
      "imageId",
      "jarSha256",
      "migrationFingerprint",
      "buildEvidenceSha256",
    ]);
    exact(f.web, [
      "kind",
      "sourceSha",
      "sourceTree",
      "controllerSourceSha",
      "dependencyLockSha256",
      "toolchainSha256",
    ]);
    check(
      digest(f.stageSha256) &&
        f.backend.sourceSha === d[phase].backend &&
        sha(f.backend.sourceTree) &&
        image(f.backend.imageId) &&
        ["jarSha256", "migrationFingerprint", "buildEvidenceSha256"].every((k) => digest(f.backend[k])) &&
        f.web.kind === "vite-source-runtime" &&
        f.web.sourceSha === d[phase].web &&
        sha(f.web.sourceTree) &&
        sha(f.web.controllerSourceSha) &&
        digest(f.web.dependencyLockSha256) &&
        digest(f.web.toolchainSha256),
      "INVALID_CANDIDATE",
    );
  }
  return d;
}
export function validateCandidateRequest(r) {
  exact(
    r,
    [
      "protocolVersion",
      "operation",
      "transactionId",
      "installationManifestPath",
      "targetDescriptorPath",
      "targetDescriptorSha256",
      "evidenceDirectory",
      "candidateStages",
    ],
    ["fencePath", "backupPath", "previousManifestPath", "rollbackPlanPath"],
  );
  check(
    r.protocolVersion === 2 &&
      OPERATIONS.includes(r.operation) &&
      /^[A-Za-z0-9-]{8,80}$/.test(r.transactionId) &&
      digest(r.targetDescriptorSha256),
    "INVALID_REQUEST",
  );
  exact(r.candidateStages, ["current", "target"]);
  const files = [
    r.installationManifestPath,
    r.targetDescriptorPath,
    ...Object.values(r.candidateStages),
    r.fencePath,
    r.previousManifestPath,
    r.rollbackPlanPath,
  ].filter((x) => x !== undefined);
  check(
    [...files, r.evidenceDirectory, ...(r.backupPath === undefined ? [] : [r.backupPath])].every(path) &&
      new Set([...files, r.evidenceDirectory, ...(r.backupPath === undefined ? [] : [r.backupPath])]).size ===
        files.length + 1 + (r.backupPath === undefined ? 0 : 1),
    "INVALID_REQUEST",
  );
  check(
    (r.operation === "inspect" || !!r.fencePath) &&
      (!["backup", "postflight", "fence-release", "rollback-plan"].includes(r.operation) || !!r.backupPath) &&
      (r.operation !== "rollback-plan" || !!r.previousManifestPath) &&
      (!r.rollbackPlanPath || ["postflight", "fence-release"].includes(r.operation)),
    "INVALID_REQUEST",
  );
  return r;
}
function boundJson(file, expected) {
  readPrivateJson(file);
  const bytes = readFileSync(file);
  check(hash(bytes) === expected, "CANDIDATE_HASH_MISMATCH");
  return JSON.parse(bytes);
}
function fileHash(file) {
  check(path(file) && realpathSync(file) === file, "CANDIDATE_PATH_MISMATCH");
  const s = lstatSync(file);
  check(
    s.isFile() && !s.isSymbolicLink() && !(s.mode & 0o022) && [0, process.getuid()].includes(s.uid),
    "CANDIDATE_FILE_UNPROVEN",
  );
  return hash(readFileSync(file));
}
function git(run, root, ...args) {
  return run("git", [
    "--no-replace-objects",
    "--no-optional-locks",
    "-c",
    "core.fsmonitor=false",
    "-c",
    "core.hooksPath=/dev/null",
    "-C",
    root,
    ...args,
  ])
    .toString()
    .trim();
}
function rawWeb(root, sourceSha, run) {
  check(
    path(root) &&
      realpathSync(root) === root &&
      git(run, root, "rev-parse", "--show-toplevel") === root &&
      git(run, root, "rev-parse", "HEAD") === sourceSha,
    "CANDIDATE_SOURCE_MISMATCH",
  );
  check(
    !git(run, root, "diff-index", "--cached", "--no-ext-diff", "--name-only", sourceSha, "--") &&
      !git(run, root, "ls-files", "--others", "--exclude-standard"),
    "SOURCE_NOT_CLEAN",
  );
  for (const row of git(run, root, "ls-tree", "-rz", "--full-tree", sourceSha).split("\0").filter(Boolean)) {
    const match = /^(100644|100755) blob ([a-f0-9]{40})\t(.+)$/s.exec(row);
    check(
      match && !isAbsolute(match[3]) && !match[3].split("/").some((p) => [".", "..", ".git"].includes(p)),
      "UNSUPPORTED_SOURCE_ENTRY",
    );
    const file = join(root, match[3]),
      s = lstatSync(file);
    check(
      s.isFile() &&
        !s.isSymbolicLink() &&
        !!(s.mode & 0o100) === (match[1] === "100755") &&
        realpathSync(file) === file &&
        git(run, root, "hash-object", "--no-filters", "--", file) === match[2],
      "RAW_SOURCE_MISMATCH",
    );
  }
  return git(run, root, "rev-parse", `${sourceSha}^{tree}`);
}
async function artifact(stageFile, frame, expectedPair, m, run) {
  const snapshot = boundJson(stageFile, frame.stageSha256);
  exact(snapshot, ["stage", "webToolchain"]);
  const s = snapshot.stage,
    t = snapshot.webToolchain;
  check(
    s?.schemaVersion === 1 &&
      s.instanceId === m.instanceId &&
      same(s.target, expectedPair) &&
      s.imageId === frame.backend.imageId &&
      s.image === `${m.backend.project}-backend:${expectedPair.backend}` &&
      s.publication === undefined,
    "CANDIDATE_STAGE_MISMATCH",
  );
  exact(s.tooling, ["webRoot", "webSource", "backendRoot", "backendSource"]);
  check(
    path(s.backendRoot) &&
      realpathSync(s.backendRoot) === s.backendRoot &&
      path(s.tooling.backendRoot) &&
      realpathSync(s.tooling.backendRoot) === s.tooling.backendRoot,
    "CANDIDATE_PATH_MISMATCH",
  );
  const identity = await inspectSource(s.backendRoot, expectedPair.backend);
  check(
    git(run, s.backendRoot, "rev-parse", `${expectedPair.backend}^{tree}`) === frame.backend.sourceTree &&
      rawWeb(s.webRoot, expectedPair.web, run) === frame.web.sourceTree &&
      s.tooling.webSource === frame.web.controllerSourceSha,
    "CANDIDATE_SOURCE_MISMATCH",
  );
  rawWeb(s.tooling.webRoot, s.tooling.webSource, run);
  await inspectSource(s.tooling.backendRoot, s.tooling.backendSource);
  check(
    fileHash(join(s.webRoot, "pnpm-lock.yaml")) === frame.web.dependencyLockSha256,
    "CANDIDATE_LOCK_MISMATCH",
  );
  const keys = ["nodeExecutablePath", "nodeExecutableSha256", "packageManagerPath", "packageManagerSha256"];
  exact(t, keys);
  check(
    fileHash(t.nodeExecutablePath) === t.nodeExecutableSha256 &&
      fileHash(t.packageManagerPath) === t.packageManagerSha256 &&
      hash(JSON.stringify(Object.fromEntries(keys.map((k) => [k, t[k]])))) === frame.web.toolchainSha256,
    "CANDIDATE_TOOLCHAIN_MISMATCH",
  );
  // Existing source-served runtime needs prepared dependencies; never run selected tool paths.
  const vite = realpathSync(join(s.webRoot, "node_modules/vite/bin/vite.js"));
  check(vite.startsWith(`${s.webRoot}/node_modules/`), "CANDIDATE_DEPENDENCIES_UNPROVEN");
  fileHash(vite);
  const b = s.buildEvidence;
  privateDirectory(b?.stageDir);
  const build = boundJson(join(b.stageDir, "build-result.json"), frame.backend.buildEvidenceSha256);
  check(
    same(build, b) &&
      build.schemaVersion === 1 &&
      build.sourceSha === expectedPair.backend &&
      build.imageId === frame.backend.imageId &&
      build.imageTag === s.image &&
      build.jarSha256 === frame.backend.jarSha256 &&
      build.baselineSha === identity.baselineSha &&
      same(build.productTrees, identity.productTrees) &&
      build.productTree === identity.productTrees["src/main"] &&
      fileHash(join(b.stageDir, "app.jar")) === frame.backend.jarSha256,
    "CANDIDATE_BUILD_MISMATCH",
  );
  let generated = readFileSync(join(s.backendRoot, "Dockerfile"), "utf8");
  check(hash(generated) === build.DockerfileSha256, "CANDIDATE_BUILD_MISMATCH");
  exact(build.toolchain, [
    "builderRef",
    "builderImageId",
    "builderDigest",
    "runtimeRef",
    "runtimeImageId",
    "runtimeDigest",
  ]);
  for (const [key, ref] of [
    ["builder", "gradle:8.14-jdk21"],
    ["runtime", "eclipse-temurin:21-jre-alpine"],
  ]) {
    const id = build.toolchain[`${key}ImageId`],
      digest = build.toolchain[`${key}Digest`];
    check(
      build.toolchain[`${key}Ref`] === ref && image(id) && /^[a-z0-9./_-]+@sha256:[a-f0-9]{64}$/.test(digest),
      "CANDIDATE_TOOLCHAIN_MISMATCH",
    );
    const observed = JSON.parse(run("docker", ["image", "inspect", id]))[0];
    check(observed?.Id === id && observed.RepoDigests?.includes(digest), "CANDIDATE_TOOLCHAIN_MISMATCH");
    generated = generated.replace(`FROM ${ref}`, `FROM ${digest}`);
  }
  generated = generated.replaceAll("--no-daemon", "--no-daemon --max-workers=2");
  check(
    hash(generated) === build.generatedDockerfileSha256 &&
      fileHash(join(b.stageDir, "Dockerfile.build")) === build.generatedDockerfileSha256,
    "CANDIDATE_BUILD_MISMATCH",
  );
  const img = JSON.parse(run("docker", ["image", "inspect", frame.backend.imageId]))[0];
  check(
    img?.Id === frame.backend.imageId &&
      img.Config?.Labels?.["io.ultistats.instance"] === m.instanceId &&
      img.Config?.Labels?.["org.opencontainers.image.revision"] === expectedPair.backend,
    "CANDIDATE_IMAGE_MISMATCH",
  );
  const temp = mkdtempSync(join(tmpdir(), "ultistats-candidate-"));
  let container;
  try {
    container = run("docker", [
      "create",
      "--network",
      "none",
      "--label",
      `io.ultistats.instance=${m.instanceId}`,
      frame.backend.imageId,
    ])
      .toString()
      .trim();
    check(/^[a-f0-9]{64}$/.test(container), "CANDIDATE_IMAGE_MISMATCH");
    run("docker", ["cp", `${container}:/app/app.jar`, join(temp, "app.jar")]);
    check(fileHash(join(temp, "app.jar")) === frame.backend.jarSha256, "CANDIDATE_JAR_MISMATCH");
  } finally {
    if (/^[a-f0-9]{64}$/.test(container ?? "")) run("docker", ["rm", container]);
    rmSync(temp, { recursive: true, force: true });
  }
  return s;
}
export async function loadCandidateTarget(request, manifest, { run = command } = {}) {
  const r = validateCandidateRequest(request),
    d = validateCandidateDescriptor(boundJson(r.targetDescriptorPath, r.targetDescriptorSha256));
  check(
    d.transactionId === r.transactionId && same(d.installationIdentity, fixedIdentity(manifest)),
    "CANDIDATE_BINDING_MISMATCH",
  );
  const stages = {};
  const phase = r.rollbackPlanPath
    ? "current"
    : ["postflight", "fence-release"].includes(r.operation)
      ? "target"
      : r.operation === "rollback-plan" && same(pair(manifest), d.target)
        ? "target"
        : "current";
  if (phase === "current")
    check(
      hash(readFileSync(r.installationManifestPath)) === d.sourceManifestSha256,
      "CANDIDATE_MANIFEST_MISMATCH",
    );
  for (const selected of ["current", "target"])
    stages[selected] = await artifact(
      r.candidateStages[selected],
      d.artifacts[selected],
      d[selected],
      manifest,
      run,
    );
  const s = stages[phase];
  check(
    same(pair(manifest), d[phase]) &&
      manifest.root === s.webRoot &&
      manifest.backend.source === s.backendRoot &&
      manifest.backend.helper.sourceCheckout === s.backendRoot &&
      manifest.backend.imageId === s.imageId &&
      manifest.backend.image === s.image &&
      same(manifest.tooling, s.tooling) &&
      same(manifest.backend.buildEvidence, s.buildEvidence),
    "CANDIDATE_MANIFEST_MISMATCH",
  );
  if (phase === "current")
    check(
      hash(readFileSync(r.installationManifestPath)) === d.sourceManifestSha256,
      "CANDIDATE_MANIFEST_MISMATCH",
    );
  // Backend raw parity includes the complete V9 migration set. Both frames must match the common observer's actual canonical fingerprint.
  check(
    d.artifacts.current.backend.migrationFingerprint === d.artifacts.target.backend.migrationFingerprint,
    "MIGRATION_UNPROVEN",
  );
  return {
    pair: { version: "unpublished", web: d.target.web, backend: d.target.backend },
    current: { version: "unpublished", web: d.current.web, backend: d.current.backend },
    compatibility: d.compatibility,
    artifacts: d.artifacts,
    frame: d.artifacts[phase],
    sourceManifestSha256: d.sourceManifestSha256,
    binding: {
      kind: KIND,
      descriptorSha256: r.targetDescriptorSha256,
      transactionId: r.transactionId,
      installationIdentity: fixedIdentity(manifest),
    },
  };
}
