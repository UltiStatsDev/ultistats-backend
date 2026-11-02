import { createHash } from "node:crypto";
import { readFileSync, readdirSync, lstatSync, realpathSync, mkdtempSync, rmSync } from "node:fs";
import { join, resolve, isAbsolute, dirname } from "node:path";
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
function boundJson(file, expected, pins) {
  readPrivateJson(file);
  const bytes = readFileSync(file);
  check(hash(bytes) === expected, "CANDIDATE_HASH_MISMATCH");
  if (pins) remember(file, lstatSync(file), bytes, pins);
  return JSON.parse(bytes);
}
function fileHash(file, pins) {
  check(path(file) && realpathSync(file) === file, "CANDIDATE_PATH_MISMATCH");
  const s = lstatSync(file);
  check(
    s.isFile() && !s.isSymbolicLink() && !(s.mode & 0o022) && [0, process.getuid()].includes(s.uid),
    "CANDIDATE_FILE_UNPROVEN",
  );
  return remember(file, s, readFileSync(file), pins);
}
function remember(file, stat, bytes, pins, directory = false) {
  const digest = hash(bytes),
    observed = { digest, uid: stat.uid, mode: stat.mode, directory };
  if (pins) {
    check(!pins.has(file) || same(pins.get(file), observed), "CANDIDATE_INPUT_CHANGED");
    pins.set(file, observed);
  }
  return digest;
}
function pinDirectory(dir, pins) {
  const stat = lstatSync(dir);
  check(stat.isDirectory() && !stat.isSymbolicLink() && realpathSync(dir) === dir, "CANDIDATE_PATH_MISMATCH");
  return remember(dir, stat, JSON.stringify(readdirSync(dir).sort()), pins, true);
}
function pinOptional(file, pins) {
  try {
    lstatSync(file);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    check(!pins.has(file) || pins.get(file) === null, "CANDIDATE_INPUT_CHANGED");
    pins.set(file, null);
    return;
  }
  fileHash(file, pins);
}
function assertFiles(pins) {
  for (const [file, expected] of pins) {
    if (expected === null) {
      try {
        lstatSync(file);
      } catch (error) {
        if (error.code === "ENOENT") continue;
        throw error;
      }
      check(false, "CANDIDATE_INPUT_CHANGED");
    } else {
      check(
        (expected.directory ? pinDirectory(file) : fileHash(file)) === expected.digest,
        "CANDIDATE_INPUT_CHANGED",
      );
      const s = lstatSync(file);
      check(s.uid === expected.uid && s.mode === expected.mode, "CANDIDATE_INPUT_CHANGED");
    }
  }
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
function rawWeb(root, sourceSha, run, pins) {
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
  const dirs = new Set([root]);
  for (const row of git(run, root, "ls-tree", "-rz", "--full-tree", sourceSha).split("\0").filter(Boolean)) {
    const match = /^(100644|100755) blob ([a-f0-9]{40})\t(.+)$/s.exec(row);
    check(
      match && !isAbsolute(match[3]) && !match[3].split("/").some((p) => [".", "..", ".git"].includes(p)),
      "UNSUPPORTED_SOURCE_ENTRY",
    );
    const file = join(root, match[3]),
      s = lstatSync(file);
    const bytes = readFileSync(file);
    check(
      s.isFile() &&
        !s.isSymbolicLink() &&
        !!(s.mode & 0o100) === (match[1] === "100755") &&
        realpathSync(file) === file &&
        createHash("sha1").update(`blob ${bytes.length}\0`).update(bytes).digest("hex") === match[2],
      "RAW_SOURCE_MISMATCH",
    );
    if (pins) {
      remember(file, s, bytes, pins);
      let parent = dirname(file);
      while (parent !== root) {
        dirs.add(parent);
        parent = dirname(parent);
      }
    }
  }
  if (pins) {
    for (const dir of dirs) pinDirectory(dir, pins);
    const head = resolve(root, git(run, root, "rev-parse", "--git-path", "HEAD"));
    for (const name of ["HEAD", "index", "config", "packed-refs"])
      pinOptional(resolve(root, git(run, root, "rev-parse", "--git-path", name)), pins);
    const ref = /^ref: ([a-zA-Z0-9._/-]+)\s*$/.exec(readFileSync(head, "utf8"))?.[1];
    if (ref) pinOptional(resolve(root, git(run, root, "rev-parse", "--git-path", ref)), pins);
    const dotGit = join(root, ".git");
    if (lstatSync(dotGit).isFile()) fileHash(dotGit, pins);
  }
  return git(run, root, "rev-parse", `${sourceSha}^{tree}`);
}
function stateAnchor(m, pins) {
  check(
    path(m.installationRoot) &&
      realpathSync(m.installationRoot) === m.installationRoot &&
      lstatSync(m.installationRoot).isDirectory() &&
      m.stateDirectory === join(m.installationRoot, ".ultistats", m.mode) &&
      m.manifestPath === join(m.stateDirectory, "installation.json") &&
      m.backend.ingress.configPath === join(m.stateDirectory, "ingress/ingress.conf"),
    "CANDIDATE_STATE_UNPROVEN",
  );
  privateDirectory(m.stateDirectory);
  fileHash(m.backend.ingress.configPath, pins);
  return {
    installationRoot: m.installationRoot,
    stateDirectory: m.stateDirectory,
    manifestPath: m.manifestPath,
    ingressConfigPath: m.backend.ingress.configPath,
  };
}
function packageManager(s, t, m, pins) {
  const pkg = JSON.parse(readFileSync(join(s.webRoot, "package.json"), "utf8"));
  const version = /^pnpm@(\d+\.\d+\.\d+)$/.exec(pkg.packageManager ?? "")?.[1];
  check(version, "CANDIDATE_PM_UNADMITTED");
  const root = join(m.stateDirectory, `tools/pnpm-${version}/node_modules/pnpm`),
    entry = join(root, "bin/pnpm.cjs"),
    metadata = join(root, "package.json");
  check(t.packageManagerPath === entry, "CANDIDATE_PM_UNADMITTED");
  fileHash(metadata, pins);
  const installed = JSON.parse(readFileSync(metadata, "utf8"));
  check(installed.name === "pnpm" && installed.version === version, "CANDIDATE_PM_UNADMITTED");
  return fileHash(entry, pins);
}
async function artifact(stageFile, frame, expectedPair, m, run, pins) {
  const pinFile = (file) => fileHash(file, pins);
  const snapshot = boundJson(stageFile, frame.stageSha256, pins);
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
    rawWeb(s.backendRoot, expectedPair.backend, run, pins) === frame.backend.sourceTree &&
      rawWeb(s.webRoot, expectedPair.web, run, pins) === frame.web.sourceTree &&
      s.tooling.webSource === frame.web.controllerSourceSha,
    "CANDIDATE_SOURCE_MISMATCH",
  );
  rawWeb(s.tooling.webRoot, s.tooling.webSource, run, pins);
  await inspectSource(s.tooling.backendRoot, s.tooling.backendSource);
  rawWeb(s.tooling.backendRoot, s.tooling.backendSource, run, pins);
  check(
    pinFile(join(s.webRoot, "pnpm-lock.yaml")) === frame.web.dependencyLockSha256,
    "CANDIDATE_LOCK_MISMATCH",
  );
  const keys = ["nodeExecutablePath", "nodeExecutableSha256", "packageManagerPath", "packageManagerSha256"];
  exact(t, keys);
  check(t.nodeExecutablePath === realpathSync(process.execPath), "CANDIDATE_NODE_UNADMITTED");
  check(
    pinFile(t.nodeExecutablePath) === t.nodeExecutableSha256 &&
      packageManager(s, t, m, pins) === t.packageManagerSha256 &&
      hash(JSON.stringify(Object.fromEntries(keys.map((k) => [k, t[k]])))) === frame.web.toolchainSha256,
    "CANDIDATE_TOOLCHAIN_MISMATCH",
  );
  // Existing source-served runtime needs prepared dependencies; never run selected tool paths.
  const vite = realpathSync(join(s.webRoot, "node_modules/vite/bin/vite.js"));
  check(vite.startsWith(`${s.webRoot}/node_modules/`), "CANDIDATE_DEPENDENCIES_UNPROVEN");
  pinFile(vite);
  const b = s.buildEvidence;
  privateDirectory(b?.stageDir);
  const build = boundJson(join(b.stageDir, "build-result.json"), frame.backend.buildEvidenceSha256, pins);
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
      pinFile(join(b.stageDir, "app.jar")) === frame.backend.jarSha256,
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
      pinFile(join(b.stageDir, "Dockerfile.build")) === build.generatedDockerfileSha256,
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
  const pins = new Map();
  const r = validateCandidateRequest(request),
    d = validateCandidateDescriptor(boundJson(r.targetDescriptorPath, r.targetDescriptorSha256, pins));
  check(
    d.transactionId === r.transactionId && same(d.installationIdentity, fixedIdentity(manifest)),
    "CANDIDATE_BINDING_MISMATCH",
  );
  const anchor = stateAnchor(manifest, pins);
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
      pins,
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
  assertFiles(pins);
  return {
    assertFiles: () => assertFiles(pins),
    stateAnchor: anchor,
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
