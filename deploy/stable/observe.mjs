import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  BASELINE,
  check,
  hash,
  flywayChecksum,
  validateManifest,
} from "./helper-contract.mjs";
import { verifyIdentity, verifyQuietPoint } from "./maintenance.mjs";

export function command(
  program,
  args,
  {
    input,
    encoding = "utf8",
    timeout = 30000,
    maxBuffer = 16 * 1024 * 1024,
  } = {},
) {
  return execFileSync(program, args, {
    input,
    encoding,
    timeout,
    maxBuffer,
    stdio: ["pipe", "pipe", "pipe"],
  });
}
export function maintenanceConfig(instance, transaction) {
  check(
    /^[a-f0-9]{12}$/.test(instance) && /^[A-Za-z0-9-]{8,80}$/.test(transaction),
    "INVALID_GATE",
  );
  return `events {}\nhttp {\n  server {\n    listen 8080;\n    add_header X-UltiStats-Instance "${instance}" always;\n    add_header X-UltiStats-Maintenance "${transaction}" always;\n    location / { return 503; }\n  }\n}\n`;
}
export function verifyMigrations(expected, rows) {
  return (
    Array.isArray(rows) &&
    rows.length === expected.length &&
    expected.every(
      (e) =>
        rows.filter(
          (r) =>
            r.version === e.version &&
            r.script === e.script &&
            r.checksum === e.checksum &&
            r.success === true,
        ).length === 1,
    )
  );
}
export function contentQuery(tables) {
  check(
    tables.length > 0 &&
      tables.length <= 64 &&
      tables.every((t) => /^[a-z_][a-z0-9_]*$/.test(t)),
    "UNKNOWN_DATABASE_LAYOUT",
  );
  return `SELECT json_agg(r ORDER BY name) FROM (${tables.map((t) => `SELECT '${t}' AS name, count(*)::text AS count, md5(coalesce(string_agg(md5(row_to_json(t)::text),'' ORDER BY row_to_json(t)::text),'')) AS digest FROM public."${t}" t`).join(" UNION ALL ")}) r;`;
}
export function sql(manifest, query, { run = command } = {}) {
  const h = manifest.backend.helper;
  return run(
    "docker",
    [
      "exec",
      "-i",
      h.databaseContainerId,
      "sh",
      "-c",
      'export PGPASSWORD="$POSTGRES_PASSWORD"; export PGAPPNAME=ultistats-stable-helper; exec psql -X -qAt --no-password --set=ON_ERROR_STOP=1 -U "$1" -d "$2"',
      "sh",
      h.databaseUser,
      h.databaseName,
    ],
    {
      input: `SET statement_timeout='20s'; SET lock_timeout='5s'; BEGIN READ ONLY; ${query}\nCOMMIT;`,
      timeout: 30000,
    },
  ).trim();
}
function shape(container, volumePath) {
  const labels = container.Config?.Labels ?? {};
  return {
    id: container.Id,
    project: labels["com.docker.compose.project"],
    instanceId: labels["io.ultistats.instance"],
    imageId: container.Image,
    running: container.State?.Running,
    volume: container.Mounts?.find(
      (m) =>
        m.Destination === volumePath && m.Type === "volume" && m.RW === true,
    )?.Name,
    networks: Object.keys(container.NetworkSettings?.Networks ?? {}),
    publishedPorts: Object.values(container.HostConfig?.PortBindings ?? {})
      .flat()
      .filter(Boolean).length,
  };
}
export function expectedIdentity(m) {
  const b = m.backend,
    h = b.helper;
  return {
    instanceId: m.instanceId,
    project: b.project,
    databaseContainerId: h.databaseContainerId,
    writerContainerIds: h.writerContainerIds,
    imageId: b.imageId,
    databaseVolume: b.databaseVolume,
    uploadsVolume: b.uploadsVolume,
    databaseNetwork: h.databaseNetwork,
    ingressContainerId: b.ingress.containerId,
    ingressImageId: b.ingress.imageId,
    origin: b.origin,
  };
}
export function fixedIdentity(m) {
  const b = m.backend,
    h = b.helper;
  return {
    instanceId: m.instanceId,
    databaseContainerId: h.databaseContainerId,
    databaseVolume: b.databaseVolume,
    uploadsVolume: b.uploadsVolume,
    origin: b.origin,
  };
}
export function pair(m) {
  return {
    version: m.release.version,
    web: m.release.webSource,
    backend: m.release.backendSource,
  };
}
export function parseCount(value) {
  check(
    typeof value === "string" &&
      /^(0|[1-9][0-9]*)$/.test(value) &&
      Number.isSafeInteger(Number(value)),
    "DATABASE_UNREADY",
  );
  return Number(value);
}
export function verifySourceIdentity(m, { run = command } = {}) {
  const b = m.backend,
    h = b.helper;
  const git = (...args) =>
    run("git", [
      "--no-replace-objects",
      "-c",
      "core.fsmonitor=false",
      "-C",
      h.sourceCheckout,
      ...args,
    ]).trim();
  let filterKeys;
  try {
    filterKeys = git(
      "config",
      "--null",
      "--name-only",
      "--get-regexp",
      "^filter\\.",
    );
  } catch (error) {
    if (error.status !== 1) throw error;
    filterKeys = "";
  }
  const filters = new Set(
    filterKeys
      .split("\0")
      .map(
        (key) =>
          /^filter\.(.+)\.(clean|smudge|process|required)$/.exec(key)?.[1],
      )
      .filter(Boolean),
  );
  const overrides = [...filters].flatMap((name) =>
    ["clean", "smudge", "process", "required"].flatMap((field) => [
      "-c",
      `filter.${name}.${field}=${field === "required" ? "false" : ""}`,
    ]),
  );
  check(
    git("rev-parse", "HEAD") === b.revision &&
      git(...overrides, "status", "--porcelain=v1", "--untracked-files=all") ===
        "",
    "SOURCE_NOT_CLEAN",
  );
  for (const p of [
    "src/main",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle",
    "gradlew",
  ])
    check(
      git("rev-parse", `${b.revision}:${p}`) ===
        git("rev-parse", `${BASELINE}:${p}`),
      "PRODUCT_PARITY_MISMATCH",
    );
  const image = JSON.parse(run("docker", ["image", "inspect", b.imageId]))[0];
  check(
    image.Id === b.imageId &&
      image.Config.Labels?.["org.opencontainers.image.revision"] ===
        b.revision &&
      image.Config.Labels?.["io.ultistats.instance"] === m.instanceId,
    "RUNTIME_UNPROVEN",
  );
}

export async function observeInstallation(
  m,
  {
    phase = "inspect",
    transactionId,
    bootstrap = false,
    allowDev = false,
    run = command,
    fetcher = fetch,
  } = {},
) {
  validateManifest(m, { bootstrap, allowDev });
  const b = m.backend,
    h = b.helper;
  const docker = (...args) => run("docker", args).trim();
  const inspect = (id) => {
    const rows = JSON.parse(docker("inspect", id));
    check(rows.length === 1 && rows[0].Id === id, "IDENTITY_MISMATCH");
    return rows[0];
  };
  const db = inspect(h.databaseContainerId),
    writers = h.writerContainerIds.map(inspect),
    ingress = inspect(b.ingress.containerId);
  const network = JSON.parse(
    docker("network", "inspect", h.databaseNetwork),
  )[0];
  const o = {
    database: shape(db, h.databaseMount),
    writers: writers.map((c) => ({
      ...shape(c, h.uploadsMount),
      uploadsVolume: shape(c, h.uploadsMount).volume,
    })),
    ingress: shape(ingress),
    network: {
      name: network.Name,
      internal: network.Internal,
      containerIds: Object.keys(network.Containers ?? {}),
    },
  };
  // Before any exec/copy/HTTP, prove exact ownership, topology, mounts and images.
  const preliminary = {
    ...o,
    databaseReady: true,
    activeMatches: 0,
    otherClients: 0,
    migrationsVerified: true,
    sourceVerified: true,
    jarVerified: true,
  };
  verifyIdentity(expectedIdentity(m), preliminary);
  check(
    db.Mounts.every(
      (x) =>
        x.Destination === h.databaseMount &&
        x.Name === b.databaseVolume &&
        x.Type === "volume",
    ),
    "IDENTITY_MISMATCH",
  );
  for (const c of writers) {
    check(
      c.Mounts.length === 1 &&
        c.Mounts[0].Destination === h.uploadsMount &&
        c.Mounts[0].Name === b.uploadsVolume &&
        c.Mounts[0].Type === "volume" &&
        c.Mounts[0].RW === true,
      "IDENTITY_MISMATCH",
    );
    check(
      c.Path === "java" &&
        JSON.stringify(c.Args) === JSON.stringify(["-jar", "app.jar"]),
      "RUNTIME_UNPROVEN",
    );
    const env = Object.fromEntries(
      (c.Config.Env ?? []).map((v) => [
        v.slice(0, v.indexOf("=")),
        v.slice(v.indexOf("=") + 1),
      ]),
    );
    check(
      env.DATABASE_URL ===
        `jdbc:postgresql://postgres:5432/${h.databaseName}` &&
        env.DATABASE_USERNAME === h.databaseUser &&
        env.APP_STORAGE_ROOT === h.uploadsMount,
      "RUNTIME_UNPROVEN",
    );
  }
  check(
    db.NetworkSettings.Networks[h.databaseNetwork].Aliases?.includes(
      "postgres",
    ),
    "DATABASE_UNREADY",
  );
  check(
    ingress.State.Running &&
      JSON.stringify(Object.keys(ingress.NetworkSettings.Networks).sort()) ===
        JSON.stringify(
          [b.ingress.backendNetwork, b.ingress.edgeNetwork].sort(),
        ),
    "INGRESS_NOT_FENCED",
  );
  const configMount = ingress.Mounts.find(
    (x) => x.Destination === "/etc/ultistats",
  );
  check(
    configMount?.Type === "bind" &&
      configMount.RW === false &&
      join(configMount.Source, "ingress.conf") === b.ingress.configPath,
    "INGRESS_NOT_FENCED",
  );
  const url = new URL(b.origin);
  const binds = ingress.HostConfig.PortBindings?.["8080/tcp"];
  check(
    Array.isArray(binds) &&
      binds.length > 0 &&
      binds.every((x) => x.HostPort === (url.port || "80")) &&
      Object.keys(ingress.HostConfig.PortBindings).length === 1,
    "INGRESS_NOT_FENCED",
  );
  verifySourceIdentity(m, { run });
  const directory = mkdtempSync(join(tmpdir(), "ultistats-helper-jar-"));
  let jarSha256;
  try {
    docker("cp", `${writers[0].Id}:/app/app.jar`, join(directory, "app.jar"));
    jarSha256 = hash(readFileSync(join(directory, "app.jar")));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
  o.jarVerified =
    (bootstrap && h.jarSha256 === null) || h.jarSha256 === jarSha256;
  o.sourceVerified = true;
  const migrationDir = join(
    h.sourceCheckout,
    "src/main/resources/db/migration",
  );
  const expected = readdirSync(migrationDir)
    .filter((n) => /^V.*\.sql$/.test(n))
    .map((script) => ({
      version: script.match(/^V([^_]+(?:_[0-9]+)?)__/)[1].replaceAll("_", "."),
      script,
      checksum: flywayChecksum(
        readFileSync(join(migrationDir, script), "utf8"),
      ),
    }));
  const migrations = JSON.parse(
    sql(
      m,
      `SELECT coalesce(json_agg(r ORDER BY version),'[]') FROM (SELECT version,script,checksum,success FROM public.flyway_schema_history WHERE type <> 'BASELINE') r;`,
      { run },
    ),
  );
  const canonical = [...migrations]
    .sort((a, c) => a.version.localeCompare(c.version))
    .map((r) => ({
      version: r.version,
      script: r.script,
      checksum: r.checksum,
      success: r.success,
    }));
  const migrationFingerprint = hash(JSON.stringify(canonical));
  o.migrationsVerified =
    verifyMigrations(expected, migrations) &&
    ((bootstrap && h.migrationFingerprint === null) ||
      h.migrationFingerprint === migrationFingerprint);
  o.activeMatches = parseCount(
    sql(
      m,
      "SELECT count(*) FROM public.matches WHERE started_at IS NOT NULL AND ended_at IS NULL;",
      { run },
    ),
  );
  const allowedIPs =
    phase !== "quiet"
      ? writers.map(
          (c) => c.NetworkSettings.Networks[h.databaseNetwork].IPAddress,
        )
      : [];
  check(
    allowedIPs.every((ip) => /^[0-9a-f:.]+$/.test(ip)),
    "NETWORK_NOT_ISOLATED",
  );
  const allowed = allowedIPs.length
    ? `AND NOT (usename='${h.databaseUser}' AND client_addr::text IN (${allowedIPs.map((ip) => `'${ip}'`).join(",")}))`
    : "";
  o.otherClients = parseCount(
    sql(
      m,
      `SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND datname=current_database() AND pid<>pg_backend_pid() ${allowed};`,
      { run },
    ),
  );
  o.databaseReady = true;
  const config = docker(
    "exec",
    ingress.Id,
    "cat",
    "/etc/ultistats/ingress.conf",
  );
  if (phase !== "inspect") {
    o.ingress.configVerified =
      config === maintenanceConfig(m.instanceId, transactionId).trim() &&
      readFileSync(b.ingress.configPath, "utf8") ===
        maintenanceConfig(m.instanceId, transactionId);
    const response = await fetcher(`${b.origin}/api/v1/matches`, {
      signal: AbortSignal.timeout(5000),
      redirect: "error",
    });
    o.ingress.maintenance =
      response.status === 503 &&
      response.headers.get("x-ultistats-instance") === m.instanceId &&
      response.headers.get("x-ultistats-maintenance") === transactionId;
    await response.body?.cancel();
  }
  verifyIdentity(expectedIdentity(m), o);
  if (phase === "quiet") verifyQuietPoint(expectedIdentity(m), o);
  if (phase === "target") {
    check(
      o.writers.every((w) => w.running === true) && o.otherClients === 0,
      "UNKNOWN_DATABASE_CLIENT",
    );
    check(
      o.ingress.maintenance === true && o.ingress.configVerified === true,
      "INGRESS_NOT_FENCED",
    );
  }
  if (phase === "inspect")
    check(
      o.writers.every((w) => w.running === true) && o.otherClients === 0,
      "RUNTIME_UNPROVEN",
    );
  return { ...o, migrationFingerprint, jarSha256 };
}
export async function observeRuntimeIdentity({ installation, ...options }) {
  const o = await observeInstallation(installation, {
    ...options,
    phase: "inspect",
    bootstrap: true,
    allowDev: true,
  });
  return {
    migrationFingerprint: o.migrationFingerprint,
    jarSha256: o.jarSha256,
  };
}
export function databaseContent(m, { run = command } = {}) {
  const tables = JSON.parse(
    sql(
      m,
      "SELECT coalesce(json_agg(tablename ORDER BY tablename),'[]') FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history';",
      { run },
    ),
  );
  const rows = JSON.parse(sql(m, contentQuery(tables), { run }));
  return hash(JSON.stringify(rows));
}
