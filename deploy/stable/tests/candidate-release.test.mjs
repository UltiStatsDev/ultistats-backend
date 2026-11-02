// Real Node CLI/private files/Git/build producer; Docker/SQL are bounded OS fixtures, not runtime proof.
import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  mkdirSync,
  readFileSync,
  writeFileSync,
  existsSync,
  realpathSync,
  symlinkSync,
  copyFileSync,
  chmodSync,
} from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fixture } from "./support/runtime-fixture.mjs";
import { buildSource } from "../build.mjs";
import { hash, readPrivateJson } from "../helper-contract.mjs";
import { fixedIdentity } from "../observe.mjs";
const candidate = await import("../candidate-target.mjs").catch(() => ({}));
const producer = await import("../candidate-release.mjs").catch(() => ({}));
const BASE = "bbd1f1b8b70699d1043376b63941d8c1f6e43e5c";
const git = (root, ...args) =>
  execFileSync("git", ["--no-replace-objects", "-c", "core.hooksPath=/dev/null", "-C", root, ...args], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
function candidateRun(program, args, options = {}) {
  if (program === "docker") {
    calls.push(args);
    if (args.includes("cat")) return readFileSync(config, "utf8");
    if (args[0] === "image" && args[1] === "ls") return "";
    if (args[0] === "image" && args[1] === "inspect") return JSON.stringify([images[args[2]]]);
    if (args[0] === "build") return "";
    if (args[0] === "create") {
      const id = hash(args.at(-1));
      created[id] = args.at(-1);
      return id;
    }
    if (args[0] === "rm") {
      delete created[args[1]];
      return "";
    }
    if (args[0] === "cp" && created[args[1].split(":")[0]]) {
      writeFileSync(args[2], images[created[args[1].split(":")[0]]].jar);
      return "";
    }
  }
  return baseRun(program, args, options);
}
async function setup(t) {
  const f = fixture(t, { sourceSha: BASE });
  const installationRoot = join(f.root, "managed-install"),
    stateDirectory = join(installationRoot, ".ultistats", "stable"),
    config = join(stateDirectory, "ingress", "ingress.conf");
  mkdirSync(dirname(config), { recursive: true, mode: 0o700 });
  copyFileSync(f.m.backend.ingress.configPath, config);
  f.m.installationRoot = installationRoot;
  f.m.stateDirectory = stateDirectory;
  f.m.manifestPath = join(stateDirectory, "installation.json");
  f.manifestPath = f.m.manifestPath;
  f.m.backend.ingress.configPath = config;
  f.ingress.Mounts[0].Source = dirname(config);
  const images = {},
    created = {},
    calls = [],
    baseRun = f.run;
  const run = new Function(
    "images",
    "created",
    "calls",
    "baseRun",
    "hash",
    "writeFileSync",
    "readFileSync",
    "config",
    `return ${candidateRun.toString()}`,
  )(images, created, calls, baseRun, hash, writeFileSync, readFileSync, config);
  for (const [name, digit, digest] of [
    ["gradle:8.14-jdk21", "8", "a"],
    ["eclipse-temurin:21-jre-alpine", "9", "b"],
  ]) {
    const image = {
      Id: `sha256:${digit.repeat(64)}`,
      RepoDigests: [`${name.split(":")[0]}@sha256:${digest.repeat(64)}`],
    };
    images[name] = image;
    images[image.Id] = image;
    images[image.RepoDigests[0]] = image;
  }
  const pm = join(stateDirectory, "tools/pnpm-11.25.0/node_modules/pnpm/bin/pnpm.cjs");
  mkdirSync(dirname(pm), { recursive: true, mode: 0o700 });
  writeFileSync(
    join(dirname(pm), "../package.json"),
    JSON.stringify({ name: "pnpm", version: "11.25.0", bin: { pnpm: "bin/pnpm.mjs" } }),
    { mode: 0o600 },
  );
  writeFileSync(pm, "#!/usr/bin/env node\nvoid import('./pnpm.mjs');\n", { mode: 0o600 });
  writeFileSync(
    join(dirname(pm), "pnpm.mjs"),
    "throw new Error('package manager fixture must never execute');\n",
    { mode: 0o600 },
  );
  const tools = {
    nodeExecutablePath: realpathSync(process.execPath),
    nodeExecutableSha256: hash(readFileSync(process.execPath)),
    packageManagerPath: pm,
    packageManagerSha256: hash(readFileSync(pm)),
  };
  const stages = {},
    frames = {};
  for (const [phase, digit] of [
    ["current", "4"],
    ["target", "7"],
  ]) {
    const backendRoot = phase === "current" ? f.source : join(f.root, "backend-target");
    if (phase === "target") {
      git(f.root, "clone", "--quiet", "--shared", "--no-checkout", resolve("."), backendRoot);
      git(backendRoot, "checkout", "--detach", git(resolve("."), "rev-parse", "HEAD"));
      writeFileSync(
        join(backendRoot, "gradlew.bat"),
        execFileSync("git", ["-C", backendRoot, "show", "HEAD:gradlew.bat"]),
      );
    }
    const sourceSha = git(backendRoot, "rev-parse", "HEAD"),
      imageTag = `${f.m.backend.project}-backend:${sourceSha}`;
    const image = {
      Id: `sha256:${digit.repeat(64)}`,
      Config: {
        Labels: { "io.ultistats.instance": f.m.instanceId, "org.opencontainers.image.revision": sourceSha },
      },
      jar: "synthetic-jar",
    };
    images[imageTag] = image;
    images[image.Id] = image;
    const webRoot = join(f.root, `web-${phase}`);
    mkdirSync(webRoot, { mode: 0o700 });
    git(webRoot, "init", "--quiet");
    git(webRoot, "config", "user.name", "Fixture");
    git(webRoot, "config", "user.email", "fixture@example.invalid");
    writeFileSync(join(webRoot, ".gitignore"), "node_modules\n");
    writeFileSync(join(webRoot, "package.json"), '{"name":"fixture","packageManager":"pnpm@11.25.0"}\n');
    writeFileSync(join(webRoot, "pnpm-lock.yaml"), `lockfileVersion: 9\n# ${phase}\n`);
    mkdirSync(join(webRoot, "scripts"));
    writeFileSync(join(webRoot, "scripts/runtime-adapter.mjs"), "// synthetic source controller\n");
    git(webRoot, "add", ".");
    git(webRoot, "commit", "--quiet", "-m", phase);
    mkdirSync(join(webRoot, "node_modules/vite/bin"), { recursive: true });
    writeFileSync(join(webRoot, "node_modules/vite/bin/vite.js"), "// prepared synthetic Vite dependency\n");
    const web = git(webRoot, "rev-parse", "HEAD"),
      target = { version: "unpublished", web, backend: sourceSha };
    const buildEvidence = await buildSource(
      {
        schemaVersion: 1,
        sourceDir: backendRoot,
        expectedSourceSha: sourceSha,
        stageDir: join(f.root, `build-${phase}`),
        imageTag,
        ownership: { instanceId: f.m.instanceId, mode: "stable", project: f.m.backend.project },
      },
      { docker: (args) => run("docker", args) },
    );
    const stage = {
      schemaVersion: 1,
      instanceId: f.m.instanceId,
      webRoot,
      backendRoot,
      target,
      image: imageTag,
      imageId: image.Id,
      buildEvidence,
      tooling: { webRoot, backendRoot, webSource: web, backendSource: sourceSha },
    };
    const snapshotPath = join(f.root, `${phase}.json`);
    f.write(snapshotPath, { stage, webToolchain: tools });
    stages[phase] = snapshotPath;
    frames[phase] = {
      stageSha256: hash(readFileSync(snapshotPath)),
      backend: {
        sourceSha,
        sourceTree: git(backendRoot, "rev-parse", "HEAD^{tree}"),
        imageId: image.Id,
        jarSha256: buildEvidence.jarSha256,
        migrationFingerprint: f.m.backend.helper.migrationFingerprint,
        buildEvidenceSha256: hash(readFileSync(join(buildEvidence.stageDir, "build-result.json"))),
      },
      web: {
        kind: "vite-source-runtime",
        sourceSha: web,
        sourceTree: git(webRoot, "rev-parse", "HEAD^{tree}"),
        controllerSourceSha: web,
        dependencyLockSha256: hash(readFileSync(join(webRoot, "pnpm-lock.yaml"))),
        toolchainSha256: hash(JSON.stringify(tools)),
      },
    };
  }
  function install(phase) {
    const { stage } = readPrivateJson(stages[phase]);
    f.m.root = stage.webRoot;
    f.m.tooling = stage.tooling;
    f.m.backend.source = stage.backendRoot;
    f.m.backend.revision = stage.target.backend;
    f.m.backend.image = stage.image;
    f.m.backend.imageId = stage.imageId;
    f.m.backend.buildEvidence = stage.buildEvidence;
    f.m.backend.helper.sourceCheckout = stage.backendRoot;
    f.m.release = {
      version: "unpublished",
      webSource: stage.target.web,
      backendSource: stage.target.backend,
    };
    f.api.Image = stage.imageId;
    f.write(f.manifestPath, f.m);
  }
  install("current");
  const descriptor = {
    schemaVersion: 1,
    kind: "owned-unpublished-candidate",
    transactionId: f.transactionId,
    installationIdentity: fixedIdentity(f.m),
    sourceManifestSha256: hash(readFileSync(f.manifestPath)),
    current: readPrivateJson(stages.current).stage.target,
    target: readPrivateJson(stages.target).stage.target,
    compatibility: {
      fromSchema: 9,
      toSchema: 9,
      appRollback: true,
      supportedSourcePairs: Object.values(stages).map((p) => {
        const { web, backend } = readPrivateJson(p).stage.target;
        return { web, backend };
      }),
    },
    artifacts: frames,
  };
  const descriptorPath = join(f.root, "descriptor.json");
  f.write(descriptorPath, descriptor);
  const base = {
    protocolVersion: 2,
    transactionId: f.transactionId,
    installationManifestPath: f.manifestPath,
    targetDescriptorPath: descriptorPath,
    targetDescriptorSha256: hash(readFileSync(descriptorPath)),
    candidateStages: stages,
    evidenceDirectory: f.root,
  };
  const request = (operation, extra = {}) => ({
    ...base,
    operation,
    ...(operation !== "inspect" ? { fencePath: f.fencePath } : {}),
    ...(["backup", "postflight", "fence-release", "rollback-plan"].includes(operation)
      ? { backupPath: f.backupPath }
      : {}),
    ...extra,
  });
  const call = (operation, extra) =>
    producer.executeCandidateRequest(request(operation, extra), { run, fetcher: f.fetcher });
  let serial = 0;
  function cli(operation, extra = {}, nodeExecutable = process.execPath) {
    if (nodeExecutable !== process.execPath) {
      assert.ok(nodeExecutable.startsWith(`${f.root}/`));
      assert.equal(hash(readFileSync(nodeExecutable)), hash(readFileSync(process.execPath)));
    }
    const requestPath = join(f.root, `request-${++serial}.json`),
      resultPath = join(f.root, `result-${serial}.json`),
      preload = join(f.root, "preload.mjs"),
      statePath = join(f.root, "os-state.json");
    f.write(requestPath, request(operation, extra));
    const variables = {
      rows: f.rows,
      images,
      created: {},
      calls: [],
      ids: f.ids,
      db: f.db,
      api: f.api,
      ingress: f.ingress,
      state: f.state,
      config: f.m.backend.ingress.configPath,
      network: f.network,
      edge: f.edge,
      image: f.m.backend.imageId,
      sha: f.m.backend.revision,
      instanceId: f.m.instanceId,
      transactionId: f.transactionId,
    };
    writeFileSync(
      preload,
      `import cp,{execFileSync as realExec} from 'node:child_process';import{syncBuiltinESMExports}from'node:module';import{readFileSync,writeFileSync,mkdirSync}from'node:fs';import{join}from'node:path';import{createHash}from'node:crypto';const hash=x=>createHash('sha256').update(x).digest('hex');const execFileSync=realExec;const {${Object.keys(variables).join(",")}}=${JSON.stringify(variables)};const baseRun=${f.run.toString()};const run=${candidateRun.toString()};cp.execFileSync=(p,a,o)=>p==='docker'?run(p,a,o):execFileSync(p,a,o);syncBuiltinESMExports();globalThis.fetch=${f.fetcher.toString()};process.on('exit',()=>writeFileSync(${JSON.stringify(statePath)},JSON.stringify({state,calls}),{mode:0o600}));`,
      { mode: 0o600 },
    );
    let status = 0,
      stderr;
    try {
      execFileSync(
        nodeExecutable,
        [
          "--import",
          preload,
          resolve("deploy/stable/candidate-release.mjs"),
          operation,
          "--request",
          requestPath,
          "--result",
          resultPath,
        ],
        { stdio: ["ignore", "pipe", "pipe"] },
      );
    } catch (e) {
      status = e.status;
      stderr = e.stderr?.toString();
    }
    if (existsSync(statePath)) {
      const saved = readPrivateJson(statePath);
      Object.assign(f.state, saved.state);
      calls.push(...saved.calls);
    }
    return { status, stderr, result: existsSync(resultPath) ? readPrivateJson(resultPath) : undefined };
  }
  calls.length = 0;
  return {
    ...f,
    run,
    request,
    call,
    cli,
    install,
    descriptor,
    descriptorPath,
    stages,
    frames,
    base,
    images,
    calls,
  };
}
test("candidate_cli_generates_bound_release", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const inspected = f.cli("inspect");
  assert.equal(inspected.status, 0, JSON.stringify(inspected));
  assert.equal(f.api.State.Running, true);
  f.state.open = false;
  f.api.State.Running = false;
  for (const op of ["fence-acquire", "fence-recheck", "backup"]) {
    const checked = f.cli(op);
    assert.equal(checked.status, 0, op);
    if (op === "backup")
      assert.equal(
        checked.result.evidence.manifestSha256,
        hash(readFileSync(join(f.backupPath, "manifest.json"))),
      );
  }
  f.install("target");
  f.api.State.Running = true;
  for (const op of ["postflight", "fence-release"]) {
    const { status, result } = f.cli(op);
    assert.equal(status, 0, op);
    assert.equal(result.protocolVersion, 2);
    assert.equal(result.evidence.binding.descriptorSha256, f.base.targetDescriptorSha256);
  }
  const release = readPrivateJson(join(f.root, `${f.transactionId}.released.json`));
  assert.equal(release.evidence.released, true);
  assert.equal(release.evidence.backupHash, hash(readFileSync(join(f.backupPath, "manifest.json"))));
  assert.equal(release.evidence.postflightSha256, hash(readFileSync(release.evidence.postflightPath)));
  assert.equal(release.evidence.installationManifestSha256, hash(readFileSync(f.manifestPath)));
  assert.equal(f.state.dumpCount, 1);
});
test("candidate_rollback_uses_real_previous_frame", async (t) => {
  const f = await setup(t),
    previousManifestPath = join(f.root, "previous.json");
  f.write(previousManifestPath, f.m);
  f.api.State.Running = false;
  for (const op of ["fence-acquire", "backup"]) assert.equal(f.cli(op).status, 0, op);
  f.install("target");
  const plan = f.cli("rollback-plan", { previousManifestPath });
  assert.equal(plan.status, 0);
  f.install("current");
  f.api.State.Running = true;
  const rollbackPlanPath = plan.result.evidence.rollbackPlanPath;
  assert.equal(f.cli("postflight", { rollbackPlanPath }).status, 0);
  const released = f.cli("fence-release", { rollbackPlanPath });
  assert.equal(released.status, 0);
  assert.equal(released.result.evidence.current.backend, f.descriptor.current.backend);
  assert.equal(released.result.evidence.released, true);
});
test("strict candidate selectors and published v1 cannot cross protocols", async (t) => {
  const f = await setup(t);
  const { executeRequest, verifyFence } = await import("../backend-release.mjs");
  await assert.rejects(() => executeRequest(f.request("inspect")));
  await assert.rejects(() =>
    producer.executeCandidateRequest({ ...f.base, operation: "inspect", protocolVersion: 1 }),
  );
  for (const patch of [
    { targetRecordPath: f.recordPath },
    { allowUnpublished: true },
    { operation: "start" },
    { candidateStages: { current: f.stages.current, target: f.stages.current } },
    { targetDescriptorPath: f.manifestPath },
    { backupPath: f.manifestPath },
  ])
    assert.throws(() => candidate.validateCandidateRequest({ ...f.request("backup"), ...patch }));
  assert.throws(() =>
    verifyFence(
      { protocolVersion: 1, transactionId: f.transactionId, identity: fixedIdentity(f.m) },
      { transactionId: f.transactionId },
      "x",
      fixedIdentity(f.m),
    ),
  );
  assert.equal((await import("../backend-release.mjs")).executeCore, undefined);
  assert.equal(producer.executeCore, undefined);
});
test("descriptor mutations cannot authorize SQL backup or application lifecycle", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const cases = {
    unknown: (d) => (d.skip = true),
    published: (d) => (d.target.version = "v0.1.0"),
    kind: (d) => (d.kind = "finalized"),
    duplicate: (d) => d.compatibility.supportedSourcePairs.push(d.compatibility.supportedSourcePairs[0]),
    transaction: (d) => (d.transactionId = "another-transaction"),
    instance: (d) => (d.installationIdentity.instanceId = "1".repeat(12)),
    manifest: (d) => (d.sourceManifestSha256 = "0".repeat(64)),
    source: (d) => (d.artifacts.target.backend.sourceSha = "0".repeat(40)),
    tree: (d) => (d.artifacts.target.backend.sourceTree = "0".repeat(40)),
    stage: (d) => (d.artifacts.target.stageSha256 = "0".repeat(64)),
    image: (d) => (d.artifacts.target.backend.imageId = `sha256:${"0".repeat(64)}`),
    jar: (d) => (d.artifacts.target.backend.jarSha256 = "0".repeat(64)),
    build: (d) => (d.artifacts.target.backend.buildEvidenceSha256 = "0".repeat(64)),
    controller: (d) => (d.artifacts.target.web.controllerSourceSha = "0".repeat(40)),
    lock: (d) => (d.artifacts.target.web.dependencyLockSha256 = "0".repeat(64)),
    tools: (d) => (d.artifacts.target.web.toolchainSha256 = "0".repeat(64)),
    runtimeKind: (d) => (d.artifacts.target.web.kind = "production-image"),
    migration: (d) => (d.artifacts.target.backend.migrationFingerprint = "0".repeat(64)),
  };
  for (const [name, mutate] of Object.entries(cases))
    await t.test(name, async () => {
      const d = structuredClone(f.descriptor);
      mutate(d);
      f.write(f.descriptorPath, d);
      f.base.targetDescriptorSha256 = hash(readFileSync(f.descriptorPath));
      f.calls.length = 0;
      await assert.rejects(() => f.call("inspect"));
      assert.equal(f.state.dumpCount, 0);
      assert.equal(f.api.State.Running, true);
      assert.equal(
        f.calls.some(
          (a) =>
            ["start", "stop", "restart", "build"].includes(a[0]) || a.some((v) => v.includes(" pg_dump ")),
        ),
        false,
      );
    });
});
test("raw stage build result source tool and image bytes are independently observed", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const target = readPrivateJson(f.stages.target),
    stage = target.stage;
  const files = {
    stage: f.stages.target,
    build: join(stage.buildEvidence.stageDir, "build-result.json"),
    jar: join(stage.buildEvidence.stageDir, "app.jar"),
    generated: join(stage.buildEvidence.stageDir, "Dockerfile.build"),
    tool: target.webToolchain.packageManagerPath,
    lock: join(stage.webRoot, "pnpm-lock.yaml"),
    source: join(stage.backendRoot, "deploy/stable/build.mjs"),
    controller: join(stage.tooling.webRoot, "scripts/runtime-adapter.mjs"),
  };
  for (const [name, file] of Object.entries(files))
    await t.test(name, async () => {
      const raw = readFileSync(file);
      writeFileSync(file, Buffer.concat([raw, Buffer.from("\n")]));
      try {
        await assert.rejects(() => f.call("inspect"));
        assert.equal(f.state.dumpCount, 0);
      } finally {
        writeFileSync(file, raw);
      }
    });
  const id = f.frames.target.backend.imageId;
  f.images[id].jar = "substituted-jar";
  await assert.rejects(() => f.call("inspect"), /CANDIDATE_JAR_MISMATCH/);
  f.images[id].jar = "synthetic-jar";
  f.images[id].Config.Labels["org.opencontainers.image.revision"] = "0".repeat(40);
  await assert.rejects(() => f.call("inspect"), /CANDIDATE_IMAGE_MISMATCH/);
});
test("cross-kind fence backup and postflight cannot be replayed and mutations stay maintenance", async (t) => {
  const f = await setup(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  const original = readPrivateJson(f.fencePath);
  f.write(f.fencePath, { ...original, protocolVersion: 1 });
  await assert.rejects(() => f.call("backup"), /FENCE_MISMATCH/);
  f.write(f.fencePath, original);
  await assert.rejects(() => f.call("fence-release"));
  assert.equal(existsSync(join(f.root, `${f.transactionId}.released.json`)), false);
  await f.call("backup");
  const backupPath = join(f.backupPath, "manifest.json"),
    backup = readPrivateJson(backupPath);
  f.install("target");
  f.api.State.Running = true;
  f.write(backupPath, { ...backup, protocolVersion: 1 });
  await assert.rejects(() => f.call("postflight"), /BACKUP_INCOMPLETE/);
  f.write(backupPath, backup);
  await f.call("postflight");
  const postPath = join(f.root, `${f.transactionId}.postflight.json`),
    post = readPrivateJson(postPath);
  f.write(postPath, { ...post, protocolVersion: 1 });
  await assert.rejects(() => f.call("fence-release"), /POSTFLIGHT_MISMATCH/);
  f.write(postPath, post);
  f.state.activeMatches = 1;
  await assert.rejects(() => f.call("fence-release"), /MATCH_IN_PROGRESS/);
  f.state.activeMatches = 0;
  writeFileSync(f.descriptorPath, readFileSync(f.descriptorPath, "utf8") + "\n");
  const refused = f.cli("fence-release");
  assert.equal(refused.status, 3);
  assert.equal(refused.result.disposition, "maintenance_required");
  assert.equal(existsSync(join(f.root, `${f.transactionId}.released.json`)), false);
});
test("common quiet active client capacity and partial-backup guards remain real", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  f.state.activeMatches = 1;
  await assert.rejects(() => f.call("inspect"), /MATCH_IN_PROGRESS/);
  f.state.activeMatches = 0;
  f.state.open = false;
  await assert.rejects(() => f.call("fence-acquire"), /WRITER_RUNNING/);
  f.api.State.Running = false;
  f.state.otherClients = 1;
  await assert.rejects(() => f.call("fence-acquire"), /UNKNOWN_DATABASE_CLIENT/);
  f.state.otherClients = 0;
  await f.call("fence-acquire");
  f.state.throwDump = true;
  await assert.rejects(() => f.call("backup"));
  assert.equal(existsSync(join(f.backupPath, "manifest.json")), false);
  assert.equal(existsSync(join(f.backupPath, "incomplete.json")), true);
});
test("CLI requires a fresh distinct result before effects and public CLI rejects v2", async (t) => {
  const f = await setup(t);
  const requestPath = join(f.root, "cli-request.json"),
    resultPath = join(f.root, "existing.json");
  f.write(requestPath, f.request("inspect"));
  f.write(resultPath, { sentinel: true });
  const { main } = await import("../candidate-release.mjs");
  assert.equal(await main(["inspect", "--request", requestPath, "--result", resultPath]), 64);
  assert.deepEqual(readPrivateJson(resultPath), { sentinel: true });
  assert.equal(await main(["inspect", "--request", requestPath, "--result", f.stages.current]), 64);
  const old = await import("../backend-release.mjs");
  assert.equal(
    await old.main(["inspect", "--request", requestPath, "--result", join(f.root, "v1-result.json")]),
    64,
  );
  assert.equal(existsSync(join(f.root, "v1-result.json")), false);
});
test("caller mutation during async verification cannot change the checked protocol", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const request = f.request("inspect");
  let changed = false;
  const result = await producer.executeCandidateRequest(request, {
    fetcher: f.fetcher,
    run: (program, args, options) => {
      if (!changed && program === "docker") {
        changed = true;
        request.protocolVersion = 1;
      }
      return f.run(program, args, options);
    },
  });
  assert.equal(result.protocolVersion, 2);
  assert.equal(result.operation, "inspect");
});
test("descriptor mutation during a guard observation refuses before a fence is written", async (t) => {
  const f = await setup(t);
  f.api.State.Running = false;
  let changed = false;
  await assert.rejects(
    () =>
      producer.executeCandidateRequest(f.request("fence-acquire"), {
        fetcher: f.fetcher,
        run: (program, args, options = {}) => {
          if (!changed && options.input?.includes("flyway_schema_history WHERE")) {
            changed = true;
            writeFileSync(f.descriptorPath, readFileSync(f.descriptorPath, "utf8") + "\n");
          }
          return f.run(program, args, options);
        },
      }),
    /CANDIDATE_HASH_MISMATCH/,
  );
  assert.equal(existsSync(f.fencePath), false);
});
test("target manifest raw bytes cannot change while postflight is observing", async (t) => {
  const f = await setup(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  await f.call("backup");
  f.install("target");
  f.api.State.Running = true;
  let changed = false;
  await assert.rejects(
    () =>
      producer.executeCandidateRequest(f.request("postflight"), {
        fetcher: f.fetcher,
        run: (program, args, options = {}) => {
          if (!changed && options.input?.includes("flyway_schema_history WHERE")) {
            changed = true;
            writeFileSync(f.manifestPath, readFileSync(f.manifestPath, "utf8") + "\n");
          }
          return f.run(program, args, options);
        },
      }),
    /CANDIDATE_MANIFEST_MISMATCH/,
  );
  assert.equal(existsSync(join(f.root, `${f.transactionId}.postflight.json`)), false);
});

test("dangling result symlink is refused before candidate validation or effects", async (t) => {
  const f = await setup(t),
    requestPath = join(f.root, "dangling-request.json"),
    resultPath = join(f.root, "dangling-result.json");
  f.write(requestPath, { ...f.request("inspect"), targetDescriptorSha256: "0".repeat(64) });
  symlinkSync(join(f.root, "absent"), resultPath);
  const { main } = await import("../candidate-release.mjs");
  assert.equal(await main(["inspect", "--request", requestPath, "--result", resultPath]), 64);
});
test("fenced current runtime cannot be substituted before backup", async (t) => {
  const f = await setup(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  const fence = readPrivateJson(f.fencePath);
  fence.runtime.imageId = `sha256:${"0".repeat(64)}`;
  f.write(f.fencePath, fence);
  await assert.rejects(() => f.call("backup"), /FENCE_MISMATCH/);
  assert.equal(f.state.dumpCount, 0);
});
test("self-consistent unrelated Node file cannot replace the admitted executable", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const other = join(f.root, "not-node.txt");
  writeFileSync(other, "not the admitted Node\n", { mode: 0o600 });
  const snap = readPrivateJson(f.stages.target);
  snap.webToolchain.nodeExecutablePath = other;
  snap.webToolchain.nodeExecutableSha256 = hash(readFileSync(other));
  f.write(f.stages.target, snap);
  f.descriptor.artifacts.target.stageSha256 = hash(readFileSync(f.stages.target));
  f.descriptor.artifacts.target.web.toolchainSha256 = hash(JSON.stringify(snap.webToolchain));
  f.write(f.descriptorPath, f.descriptor);
  f.base.targetDescriptorSha256 = hash(readFileSync(f.descriptorPath));
  await assert.rejects(() => f.call("inspect"));
  assert.equal(f.state.dumpCount, 0);
});
for (const kind of ["backup", "fence", "descriptor", "dump", "uploads", "settings"])
  test(`bound ${kind} bytes are rechecked after final await`, async (t) => {
    const f = await setup(t);
    let operation = "inspect",
      file = f.descriptorPath;
    if (kind !== "descriptor") {
      f.api.State.Running = false;
      await f.call("fence-acquire");
      operation = "fence-recheck";
      file = f.fencePath;
      if (["backup", "dump", "uploads", "settings"].includes(kind)) {
        await f.call("backup");
        f.install("target");
        f.api.State.Running = true;
        await f.call("postflight");
        operation = "fence-release";
        file = join(
          f.backupPath,
          kind === "dump"
            ? "database.dump"
            : kind === "uploads"
              ? "uploads/photo.png"
              : kind === "settings"
                ? "settings-0"
                : "manifest.json",
        );
      }
    } else f.state.open = true;
    let changed = false;
    await assert.rejects(() =>
      producer.executeCandidateRequest(f.request(operation), {
        run: f.run,
        fetcher: async (...args) => {
          const response = await f.fetcher(...args);
          if (!changed) {
            changed = true;
            writeFileSync(file, readFileSync(file, "utf8") + "\n");
          }
          return response;
        },
      }),
    );
    assert.equal(existsSync(join(f.root, `${f.transactionId}.released.json`)), false);
  });
test("earlier stage is rechecked after the last target artifact observation", async (t) => {
  const f = await setup(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  await f.call("backup");
  f.install("target");
  f.api.State.Running = true;
  await f.call("postflight");
  let targetChecks = 0,
    changed = false;
  await assert.rejects(() =>
    producer.executeCandidateRequest(f.request("fence-release"), {
      fetcher: f.fetcher,
      run: (program, args, options) => {
        if (
          program === "docker" &&
          args[0] === "image" &&
          args[1] === "inspect" &&
          args[2] === f.frames.target.backend.imageId &&
          ++targetChecks === 4
        ) {
          changed = true;
          writeFileSync(f.stages.current, readFileSync(f.stages.current, "utf8") + "\n");
        }
        return f.run(program, args, options);
      },
    }),
  );
  assert.equal(changed, true);
  assert.equal(existsSync(join(f.root, `${f.transactionId}.released.json`)), false);
});
test("private actual Node copy is admitted only when its own mode is safe", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const node = join(f.root, "owned-node");
  copyFileSync(realpathSync(process.execPath), node);
  assert.notEqual(node, realpathSync(process.execPath));
  for (const phase of ["current", "target"]) {
    const snap = readPrivateJson(f.stages[phase]);
    snap.webToolchain.nodeExecutablePath = node;
    snap.webToolchain.nodeExecutableSha256 = hash(readFileSync(node));
    f.write(f.stages[phase], snap);
    f.descriptor.artifacts[phase].stageSha256 = hash(readFileSync(f.stages[phase]));
    f.descriptor.artifacts[phase].web.toolchainSha256 = hash(JSON.stringify(snap.webToolchain));
  }
  f.write(f.descriptorPath, f.descriptor);
  f.base.targetDescriptorSha256 = hash(readFileSync(f.descriptorPath));
  chmodSync(node, 0o775);
  const refused = f.cli("inspect", {}, node);
  assert.equal(refused.status, 2);
  assert.equal(refused.result.reason, "CANDIDATE_FILE_UNPROVEN");
  chmodSync(node, 0o700);
  const accepted = f.cli("inspect", {}, node);
  assert.equal(accepted.status, 0, JSON.stringify(accepted));
});
test("final target read cannot stale earlier tool source or directory evidence", async (t) => {
  const f = await setup(t);
  f.api.State.Running = false;
  await f.call("fence-acquire");
  await f.call("backup");
  f.install("target");
  f.api.State.Running = true;
  await f.call("postflight");
  const current = readPrivateJson(f.stages.current);
  const files = [
    current.webToolchain.packageManagerPath,
    join(current.stage.backendRoot, "Dockerfile"),
    join(current.stage.webRoot, "late-untracked.txt"),
  ];
  for (const file of files) {
    assert.ok(file.startsWith(`${f.root}/`));
    const original = existsSync(file) ? readFileSync(file) : null;
    let checks = 0,
      changed = false;
    try {
      await assert.rejects(() =>
        producer.executeCandidateRequest(f.request("fence-release"), {
          fetcher: f.fetcher,
          run: (program, args, options) => {
            if (
              program === "docker" &&
              args[0] === "image" &&
              args[1] === "inspect" &&
              args[2] === f.frames.target.backend.imageId &&
              ++checks === 4
            ) {
              changed = true;
              writeFileSync(file, Buffer.concat([original ?? Buffer.alloc(0), Buffer.from("\n")]), {
                mode: 0o600,
              });
            }
            return f.run(program, args, options);
          },
        }),
      );
      assert.equal(changed, true);
      assert.equal(existsSync(join(f.root, `${f.transactionId}.released.json`)), false);
    } finally {
      if (original) writeFileSync(file, original);
      else (await import("node:fs")).unlinkSync(file);
    }
  }
});
test("self-consistent other file is not the source-derived managed pnpm entrypoint", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const file = join(f.root, "other-pm.txt");
  writeFileSync(file, "unrelated bytes\n", { mode: 0o600 });
  const snap = readPrivateJson(f.stages.target);
  snap.webToolchain.packageManagerPath = file;
  snap.webToolchain.packageManagerSha256 = hash(readFileSync(file));
  f.write(f.stages.target, snap);
  f.descriptor.artifacts.target.stageSha256 = hash(readFileSync(f.stages.target));
  f.descriptor.artifacts.target.web.toolchainSha256 = hash(JSON.stringify(snap.webToolchain));
  f.write(f.descriptorPath, f.descriptor);
  f.base.targetDescriptorSha256 = hash(readFileSync(f.descriptorPath));
  await assert.rejects(() => f.call("inspect"));
  assert.equal(f.state.dumpCount, 0);
});
test("PM role refuses wrong managed cache metadata version root and bin selector", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const original = structuredClone(f.descriptor),
    snapshot = readPrivateJson(f.stages.target),
    pm = snapshot.webToolchain.packageManagerPath,
    metadata = join(dirname(pm), "../package.json"),
    rawMetadata = readFileSync(metadata);
  for (const field of ["name", "version"]) {
    f.write(metadata, { name: "pnpm", version: "11.25.0", [field]: "other" });
    await assert.rejects(() => f.call("inspect"), /CANDIDATE_PM_UNADMITTED/);
    writeFileSync(metadata, rawMetadata);
  }
  for (const file of [
    join(f.root, "other-tools/pnpm-11.25.0/node_modules/pnpm/bin/pnpm.cjs"),
    join(f.m.stateDirectory, "tools/pnpm-99.0.0/node_modules/pnpm/bin/pnpm.cjs"),
    join(dirname(pm), "pnpm.mjs"),
  ]) {
    mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
    if (!existsSync(file)) writeFileSync(file, readFileSync(pm), { mode: 0o600 });
    const snap = structuredClone(snapshot);
    snap.webToolchain.packageManagerPath = file;
    snap.webToolchain.packageManagerSha256 = hash(readFileSync(file));
    f.write(f.stages.target, snap);
    f.descriptor = structuredClone(original);
    f.descriptor.artifacts.target.stageSha256 = hash(readFileSync(f.stages.target));
    f.descriptor.artifacts.target.web.toolchainSha256 = hash(JSON.stringify(snap.webToolchain));
    f.write(f.descriptorPath, f.descriptor);
    f.base.targetDescriptorSha256 = hash(readFileSync(f.descriptorPath));
    await assert.rejects(() => f.call("inspect"), /CANDIDATE_PM_UNADMITTED/);
  }
  assert.equal(f.state.dumpCount, 0);
});
test("private DTO filename is independent of protected internal installation anchor", async (t) => {
  const f = await setup(t);
  f.state.open = true;
  const dto = join(f.root, "previous-manifest-dto.json");
  f.write(dto, f.m);
  f.base.installationManifestPath = dto;
  assert.notEqual(dto, f.m.manifestPath);
  assert.equal((await f.call("inspect")).evidence.ready, true);
  f.m.stateDirectory = join(f.root, "unowned-state");
  f.write(dto, f.m);
  f.descriptor.sourceManifestSha256 = hash(readFileSync(dto));
  f.write(f.descriptorPath, f.descriptor);
  f.base.targetDescriptorSha256 = hash(readFileSync(f.descriptorPath));
  await assert.rejects(() => f.call("inspect"), /CANDIDATE_STATE_UNPROVEN/);
});
