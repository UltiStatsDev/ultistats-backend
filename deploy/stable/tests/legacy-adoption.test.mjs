import test from "node:test";
import assert from "node:assert/strict";
const api = await import("../legacy-adoption.mjs").catch(() => ({}));
const id = (n) => String(n).repeat(64),
  image = (n) => `sha256:${id(n)}`;
function fixture() {
  const project = "owned-legacy",
    root = "/tmp/owned-old39",
    instanceId = "000000000162";
  const claim = {
    schemaVersion: 1,
    project,
    legacyRoot: root,
    apiContainer: id(1),
    dbContainer: id(2),
    webSource: "39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba",
    backendSource: "24704e11143a0bf919559a41324603f936ae03cf",
    apiOrigin: "http://127.0.0.1:21462",
    webOrigin: "http://127.0.0.1:21463",
    expectedJarSha256: id(3),
    databaseName: "ultistats",
    databaseUser: "ultistats",
    settingsPaths: [],
  };
  const installation = {
    root,
    schemaVersion: 1,
    mode: "stable",
    instanceId,
    web: { origin: claim.webOrigin },
    release: {
      version: "unpublished",
      webSource: claim.webSource,
      backendSource: claim.backendSource,
    },
    backend: {
      ownership: "managed",
      project,
      source: `${root}/dev/backend/.local/source`,
      revision: claim.backendSource,
      imageId: image(4),
      databaseVolume: `${project}_database`,
      uploadsVolume: `${project}_uploads`,
      origin: claim.apiOrigin,
    },
    legacy: {
      schemaVersion: 1,
      root,
      apiContainer: id(1),
      dbContainer: id(2),
      imageId: image(4),
    },
  };
  const network = `${project}_default`,
    labels = (service) => ({
      "com.docker.compose.project": project,
      "com.docker.compose.service": service,
      "com.docker.compose.project.working_dir": root,
    });
  const app = {
    Id: id(1),
    Image: image(4),
    Path: "java",
    Args: ["-jar", "app.jar"],
    State: { Running: true },
    Config: {
      Labels: labels("api"),
      Env: [
        "DATABASE_URL=jdbc:postgresql://postgres:5432/ultistats",
        "DATABASE_USERNAME=ultistats",
        "DATABASE_PASSWORD=fixture-private",
        "APP_STORAGE_ROOT=/app/uploads",
      ],
    },
    HostConfig: {
      PortBindings: {
        "8080/tcp": [{ HostIp: "127.0.0.1", HostPort: "21462" }],
      },
    },
    NetworkSettings: {
      Networks: { [network]: { IPAddress: "172.30.0.3", Aliases: ["api"] } },
    },
    Mounts: [
      {
        Type: "volume",
        Name: `${project}_uploads`,
        Destination: "/app/uploads",
        RW: true,
      },
    ],
  };
  const db = {
    Id: id(2),
    Image: image(5),
    State: { Running: true },
    Config: {
      Labels: labels("postgres"),
      Env: [
        "POSTGRES_DB=ultistats",
        "POSTGRES_USER=ultistats",
        "POSTGRES_PASSWORD=fixture-private",
      ],
    },
    HostConfig: {
      PortBindings: {
        "5432/tcp": [{ HostIp: "127.0.0.1", HostPort: "22462" }],
      },
    },
    NetworkSettings: {
      Networks: {
        [network]: { IPAddress: "172.30.0.2", Aliases: ["postgres"] },
      },
    },
    Mounts: [
      {
        Type: "volume",
        Name: `${project}_database`,
        Destination: "/var/lib/postgresql/data",
        RW: true,
      },
    ],
  };
  const networks = [
      { Name: network, Containers: { [id(1)]: {}, [id(2)]: {} } },
    ],
    volumes = [
      {
        Name: `${project}_database`,
        CreatedAt: "2026-11-02T00:00:00Z",
        Driver: "local",
      },
      {
        Name: `${project}_uploads`,
        CreatedAt: "2026-11-02T00:00:00Z",
        Driver: "local",
      },
    ];
  return {
    installation,
    claim,
    app,
    db,
    networks,
    volumes,
    containerIds: [id(1), id(2)],
  };
}
test("legacy resource proof accepts exact owned old39/247 identity with loopback-only DB port", () => {
  const f = fixture();
  assert.equal(api.verifyLegacyResources(f).identity.databaseImageId, image(5));
});
for (const [name, change, reason] of [
  [
    "foreign project",
    (f) => (f.app.Config.Labels["com.docker.compose.project"] = "foreign"),
    "LEGACY_OWNERSHIP_UNPROVEN",
  ],
  [
    "different working directory",
    (f) =>
      (f.db.Config.Labels["com.docker.compose.project.working_dir"] =
        "/tmp/foreign"),
    "LEGACY_OWNERSHIP_UNPROVEN",
  ],
  [
    "extra API in project",
    (f) => f.containerIds.push(id(9)),
    "LEGACY_OWNERSHIP_UNPROVEN",
  ],
  [
    "unknown network client",
    (f) => (f.networks[0].Containers[id(9)] = { above: false }),
    "LEGACY_UNKNOWN_CLIENT",
  ],
  [
    "public DB binding",
    (f) => (f.db.HostConfig.PortBindings["5432/tcp"][0].HostIp = "0.0.0.0"),
    "LEGACY_DATABASE_EXPOSED",
  ],
  [
    "other source tuple",
    (f) => (f.claim.backendSource = "a".repeat(40)),
    "LEGACY_SOURCE_UNSUPPORTED",
  ],
  [
    "different DB volume",
    (f) => (f.db.Mounts[0].Name = "foreign-db"),
    "LEGACY_STORAGE_UNPROVEN",
  ],
  [
    "mismatched API database",
    (f) =>
      (f.app.Config.Env[0] =
        "DATABASE_URL=jdbc:postgresql://external:5432/ultistats"),
    "LEGACY_DATABASE_UNPROVEN",
  ],
  [
    "missing artifact evidence",
    (f) => delete f.claim.expectedJarSha256,
    "LEGACY_SOURCE_UNSUPPORTED",
  ],
])
  test(`legacy refuses ${name}`, () => {
    const f = fixture();
    change(f);
    assert.throws(() => api.verifyLegacyResources(f), { message: reason });
  });
test("legacy quietpoint refuses a running API before creating a SQL lease", () => {
  const f = fixture();
  assert.throws(() => api.verifyLegacyResources(f, { quiet: true }), {
    message: "LEGACY_WRITER_RUNNING",
  });
  f.app.State.Running = false;
  assert.equal(
    api.verifyLegacyResources(f, { quiet: true }).manifest.backend.helper
      .databaseContainerId,
    id(2),
  );
});
