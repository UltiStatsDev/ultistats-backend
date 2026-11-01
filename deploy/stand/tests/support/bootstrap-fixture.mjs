import { spawn } from "node:child_process";
import { readFile, writeFile, chmod } from "node:fs/promises";
import { randomBytes } from "node:crypto";

export async function fixture(planPath, t) {
  const plan = JSON.parse(await readFile(planPath, "utf8"));
  const root = plan.root,
    created = [];
  let applicationId;
  const run = (args, input = "") =>
    new Promise((resolve, reject) => {
      const child = spawn("docker", args, { stdio: ["pipe", "pipe", "pipe"] });
      let out = "",
        err = "";
      child.stdout.on("data", (b) => {
        out += b;
      });
      child.stderr.on("data", (b) => {
        err += b;
      });
      child.on("error", () => reject(new Error("Fixture Docker unavailable")));
      child.on("close", (code) =>
        code === 0
          ? resolve(out.trim())
          : reject(new Error(`Fixture Docker ${args[0]} operation rejected`)),
      );
      child.stdin.on("error", () => {});
      child.stdin.end(input);
    });
  const lifecycle = (args) =>
    new Promise((resolve, reject) => {
      const child = spawn(
        "flock",
        [
          "-w",
          "60",
          "-x",
          "/tmp/ultistats-browser-network.lock",
          "docker",
          ...args,
        ],
        { stdio: ["ignore", "pipe", "pipe"] },
      );
      let out = "";
      child.stdout.on("data", (b) => {
        out += b;
      });
      child.stderr.resume();
      child.on("error", reject);
      child.on("close", (code) =>
        code === 0
          ? resolve(out.trim())
          : reject(
              new Error("Fixture lifecycle operation or bounded lock failed"),
            ),
      );
    });
  const inspect = async (type, name) =>
    JSON.parse(await run([type, "inspect", name]))[0];
  const labels = (kind) => [
    "--label",
    "org.ultistats.installation-id=ultistats_private",
    "--label",
    `org.ultistats.runtime-instance-id=${plan.runtimeInstanceId}`,
    "--label",
    `org.ultistats.resource-kind=${kind}`,
  ];
  t.after(async () => {
    if (applicationId) {
      try {
        await writeFile(
          `${root}/actual247-api-private.log`,
          await run(["exec", applicationId, "cat", "/tmp/bootstrap-api.log"]),
          { mode: 0o600 },
        );
      } catch {}
    }
    for (const id of created.reverse()) await lifecycle(["rm", "-f", id]);
    if (networkId) await lifecycle(["network", "rm", networkId]);
    await writeFile(
      `${root}/consumer-END.json`,
      JSON.stringify(
        {
          consumer: "bootstrap169",
          state: "END",
          containersRemoved: created,
          networkRemoved: networkId,
          retainedVolume: plan.databaseVolume,
        },
        null,
        2,
      ) + "\n",
      { mode: 0o600 },
    );
  });
  let networkId;
  for (const [kind, name] of [
    ["container", plan.postgresContainer],
    ["container", plan.applicationContainer],
    ["container", plan.proxyContainer],
    ["network", plan.databaseNetwork],
    ["volume", plan.databaseVolume],
  ]) {
    let exists = false;
    try {
      await inspect(kind, name);
      exists = true;
    } catch {}
    if (exists) throw new Error("Refuse existing fixture resource");
  }
  await lifecycle([
    "volume",
    "create",
    ...labels("postgres-data"),
    plan.databaseVolume,
  ]);
  networkId = await lifecycle([
    "network",
    "create",
    "--internal",
    ...labels("network"),
    plan.databaseNetwork,
  ]);
  const passwords = Object.fromEntries(
    ["postgres", "migration", "service", "login"].map((k) => [
      k,
      randomBytes(24).toString("hex"),
    ]),
  );
  await writeFile(
    `${root}/pg.env`,
    `POSTGRES_PASSWORD=${passwords.postgres}\n`,
    { mode: 0o600 },
  );
  const containerId = await lifecycle([
    "run",
    "-d",
    "--name",
    plan.postgresContainer,
    ...labels("postgres"),
    "--network",
    plan.databaseNetwork,
    "--env-file",
    `${root}/pg.env`,
    "--mount",
    `type=volume,src=${plan.databaseVolume},dst=/var/lib/postgresql/data`,
    plan.postgresImageId,
  ]);
  created.push(containerId);
  const sql = (text, database = "postgres") =>
    run(
      [
        "exec",
        "--user",
        "postgres",
        "-i",
        containerId,
        "psql",
        "-X",
        "-qAt",
        "-w",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "postgres",
        "-d",
        database,
      ],
      text,
    );
  let ready = false;
  for (let n = 0; n < 60; n++) {
    try {
      if (
        (await run(["exec", containerId, "cat", "/proc/1/comm"])) !== "postgres"
      )
        throw new Error("initializing");
      await sql("SELECT 1");
      ready = true;
      break;
    } catch {
      await new Promise((r) => setTimeout(r, 250));
    }
  }
  if (!ready) throw new Error("Fresh fixture PostgreSQL not ready");
  const [container, volume, network] = await Promise.all([
    inspect("container", containerId),
    inspect("volume", plan.databaseVolume),
    inspect("network", networkId),
  ]);
  const systemIdentifier = await sql(
    "SELECT system_identifier FROM pg_control_system()",
  );
  const creation = {
    schemaVersion: 1,
    installationId: "ultistats_private",
    runtimeInstanceId: plan.runtimeInstanceId,
    databaseContainerId: containerId,
    databaseImageId: plan.postgresImageId,
    databaseVolume: plan.databaseVolume,
    databaseNetwork: plan.databaseNetwork,
    containerCreatedAt: container.Created,
    volumeCreatedAt: volume.CreatedAt,
    networkId,
    networkCreatedAt: network.Created,
    volumeDriver: volume.Driver,
    networkDriver: network.Driver,
    systemIdentifier,
    emptyVolumeBeforeStart: true,
  };
  // Test runner created the named volume immediately before first container start.
  await writeFile(`${root}/creation.json`, JSON.stringify(creation), {
    mode: 0o600,
  });
  for (const [file, key] of [
    ["admin", "postgres"],
    ["migration", "migration"],
    ["service", "service"],
    ["login", "login"],
  ])
    await writeFile(
      `${root}/${file}.json`,
      JSON.stringify({ password: passwords[key] }),
      { mode: 0o600 },
    );
  const options = {
    schemaVersion: 1,
    installationId: "ultistats_private",
    runtimeInstanceId: plan.runtimeInstanceId,
    databaseContainerId: containerId,
    databaseImageId: plan.postgresImageId,
    databaseVolume: plan.databaseVolume,
    databaseNetwork: plan.databaseNetwork,
    creationReceiptPath: `${root}/creation.json`,
    bootstrapReceiptPath: `${root}/bootstrap.json`,
    adminCredentialsPath: `${root}/admin.json`,
    migrationCredentialsPath: `${root}/migration.json`,
    serviceCredentialsPath: `${root}/service.json`,
    loginCredentialsPath: `${root}/login.json`,
  };
  const roles = {
    migration: "us_ultistats_private_migration",
    service: "us_ultistats_private_service",
    login: "us_ultistats_private_sql",
  };
  async function authenticated(
    key,
    text,
    {
      from = containerId,
      host = "/var/run/postgresql",
      database = "ultistats_private",
      password = passwords[key],
    } = {},
  ) {
    const role = roles[key] ?? key;
    return run(
      ["exec", "--user", "postgres", "-i", from, "sh"],
      `export PGPASSWORD='${password}'; psql -X -qAt -w -v ON_ERROR_STOP=1 -h '${host}' -U '${role}' -d '${database}' <<'BOOTSTRAP_TEST_SQL'\n${text}\nBOOTSTRAP_TEST_SQL\n`,
    );
  }
  async function startWriters() {
    const pgIp =
      container.NetworkSettings.Networks[plan.databaseNetwork].IPAddress;
    const env = `DATABASE_URL=jdbc:postgresql://${pgIp}:5432/ultistats_private\nDATABASE_USERNAME=${roles.service}\nDATABASE_PASSWORD=${passwords.service}\nSPRING_FLYWAY_USER=${roles.migration}\nSPRING_FLYWAY_PASSWORD=${passwords.migration}\nAPP_STORAGE_ROOT=/tmp/bootstrap-uploads\n`;
    await writeFile(`${root}/api.env`, env, { mode: 0o600 });
    const app = await lifecycle([
      "run",
      "-d",
      "--name",
      plan.applicationContainer,
      ...labels("writer"),
      "--network",
      plan.databaseNetwork,
      "--env-file",
      `${root}/api.env`,
      "--entrypoint",
      "sh",
      plan.applicationImageId,
      "-c",
      "sleep infinity",
    ]);
    created.push(app);
    applicationId = app;
    const proxy = await lifecycle([
      "run",
      "-d",
      "--name",
      plan.proxyContainer,
      ...labels("proxy"),
      "--network",
      plan.databaseNetwork,
      "--entrypoint",
      "sleep",
      plan.postgresImageId,
      "infinity",
    ]);
    created.push(proxy);
    return {
      proxyContainerId: proxy,
      proxyImageId: plan.postgresImageId,
      writerContainerIds: [app],
      applicationContainerId: app,
    };
  }
  return {
    root,
    plan,
    passwords,
    options,
    sql,
    authenticated,
    startWriters,
    run,
    inspect,
    roles,
    launchApplication: async (id) =>
      run([
        "exec",
        "-d",
        id,
        "sh",
        "-c",
        "exec java -jar /app/app.jar >/tmp/bootstrap-api.log 2>&1",
      ]),
    appUrl: async (id) =>
      `http://${(await inspect("container", id)).NetworkSettings.Networks[plan.databaseNetwork].IPAddress}:8080`,
  };
}
