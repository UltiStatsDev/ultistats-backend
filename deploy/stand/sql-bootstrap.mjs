import {
  open,
  readFile,
  writeFile,
  rename,
  unlink,
  mkdtemp,
  rm,
  realpath,
  stat,
} from "node:fs/promises";
import { constants } from "node:fs";
import { dirname, join, isAbsolute, resolve } from "node:path";
import { spawn } from "node:child_process";
import {
  createHash,
  createHmac,
  pbkdf2Sync,
  randomBytes,
  randomUUID,
} from "node:crypto";
import { reconcileSqlRole, rolePolicySha256 } from "./sql-roles.mjs";

const LOGICAL = "ultistats_private",
  DB = LOGICAL;
const MIGRATION = "us_ultistats_private_migration",
  SERVICE = "us_ultistats_private_service",
  LOGIN = "us_ultistats_private_sql";
const BASELINE = "24704e11143a0bf919559a41324603f936ae03cf";
const HASH = /^[a-f0-9]{64}$/,
  IMAGE = /^sha256:[a-f0-9]{64}$/;
const sha = (value) => createHash("sha256").update(value).digest("hex");
export const bootstrapSourceSha256 = sha(
  await readFile(new URL(import.meta.url)),
);
export const roleSourceSha256 = sha(
  await readFile(new URL("./sql-roles.mjs", import.meta.url)),
);
export const bootstrapContractSha256 = sha(
  await readFile(new URL("./bootstrap-contract-v1.json", import.meta.url)),
);
const q = (value) => `'${value.replaceAll("'", "''")}'`;
const id = (value) => `"${value}"`;
function check(condition, code) {
  if (!condition) throw new Error(code);
}
function validate(input) {
  check(
    input?.schemaVersion === 1 && input.installationId === LOGICAL,
    "BOOTSTRAP_IDENTITY_INVALID",
  );
  check(
    /^[a-f0-9]{12}$/.test(input.runtimeInstanceId),
    "BOOTSTRAP_NAMESPACE_INVALID",
  );
  check(
    HASH.test(input.databaseContainerId) && IMAGE.test(input.databaseImageId),
    "BOOTSTRAP_RESOURCE_ID_INVALID",
  );
  for (const key of ["databaseVolume", "databaseNetwork"])
    check(
      typeof input[key] === "string" &&
        /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/.test(input[key]),
      "BOOTSTRAP_RESOURCE_NAME_INVALID",
    );
  const result = {};
  for (const key of [
    "schemaVersion",
    "installationId",
    "runtimeInstanceId",
    "databaseContainerId",
    "databaseImageId",
    "databaseVolume",
    "databaseNetwork",
    "creationReceiptPath",
    "bootstrapReceiptPath",
    "adminCredentialsPath",
    "migrationCredentialsPath",
    "serviceCredentialsPath",
    "loginCredentialsPath",
  ])
    result[key] = input[key];
  return result;
}
async function privateParent(path) {
  check(
    typeof path === "string" && isAbsolute(path) && resolve(path) === path,
    "BOOTSTRAP_PRIVATE_PATH_REQUIRED",
  );
  const parent = dirname(path),
    s = await stat(parent);
  check(
    (await realpath(parent)) === parent &&
      s.isDirectory() &&
      s.uid === process.getuid() &&
      (s.mode & 0o077) === 0,
    "BOOTSTRAP_PRIVATE_DIRECTORY_REQUIRED",
  );
}
async function privateJson(path, optional = false) {
  let file;
  try {
    await privateParent(path);
    file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const s = await file.stat();
    check(
      s.isFile() &&
        s.uid === process.getuid() &&
        (s.mode & 0o777) === 0o600 &&
        s.size < 65536,
      "BOOTSTRAP_PRIVATE_FILE_REQUIRED",
    );
    return JSON.parse(await file.readFile("utf8"));
  } catch (error) {
    if (optional && error.code === "ENOENT") return null;
    throw new Error("BOOTSTRAP_PRIVATE_FILE_REJECTED");
  } finally {
    await file?.close();
  }
}
async function persist(path, value) {
  const temporary = `${path}.${randomBytes(8).toString("hex")}.tmp`;
  const f = await open(
    temporary,
    constants.O_CREAT |
      constants.O_EXCL |
      constants.O_WRONLY |
      constants.O_NOFOLLOW,
    0o600,
  );
  try {
    await f.writeFile(JSON.stringify(value, null, 2) + "\n");
    await f.sync();
  } finally {
    await f.close();
  }
  await rename(temporary, path);
  const directory = await open(dirname(path), "r");
  try {
    await directory.sync();
  } finally {
    await directory.close();
  }
}
function runDocker(args, input = "") {
  return new Promise((resolvePromise, reject) => {
    const child = spawn("docker", args, {
      env: { PATH: process.env.PATH, LANG: "C" },
      stdio: ["pipe", "pipe", "pipe"],
    });
    let output = "",
      overflow = false;
    const timer = setTimeout(() => child.kill("SIGTERM"), 30000);
    child.stdout.on("data", (b) => {
      output += b;
      if (output.length > 2 ** 20) {
        overflow = true;
        child.kill("SIGTERM");
      }
    });
    child.stderr.resume();
    child.stdin.on("error", () => {});
    child.on("error", () =>
      reject(new Error("BOOTSTRAP_DOCKER_TRANSPORT_FAILED")),
    );
    child.on("close", (code) => {
      clearTimeout(timer);
      code === 0 && !overflow
        ? resolvePromise(output.trim())
        : reject(new Error("BOOTSTRAP_DOCKER_OPERATION_REJECTED"));
    });
    child.stdin.end(input);
  });
}
const inspect = async (kind, name) =>
  JSON.parse(await runDocker([kind, "inspect", name]))[0];
function owned(resource, o, kind) {
  const l = resource?.Config?.Labels ?? resource?.Labels;
  return (
    l?.["org.ultistats.installation-id"] === LOGICAL &&
    l?.["org.ultistats.runtime-instance-id"] === o.runtimeInstanceId &&
    l?.["org.ultistats.resource-kind"] === kind
  );
}
const sql = (o, text, database = "postgres") =>
  runDocker(
    [
      "exec",
      "--user",
      "postgres",
      "-i",
      o.databaseContainerId,
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
const jsonSql = async (o, text, database) =>
  JSON.parse(await sql(o, text, database));
function marker(o, systemIdentifier) {
  return `ultistats-bootstrap:${LOGICAL}:${o.runtimeInstanceId}:${systemIdentifier}`;
}
async function observe(o, creation, allowed = []) {
  const [container, volume, network] = await Promise.all([
    inspect("container", o.databaseContainerId),
    inspect("volume", o.databaseVolume),
    inspect("network", o.databaseNetwork),
  ]);
  check(
    container.Id === o.databaseContainerId &&
      container.Image === o.databaseImageId &&
      container.State?.Running &&
      owned(container, o, "postgres"),
    "BOOTSTRAP_CONTAINER_MISMATCH",
  );
  check(
    container.Created === creation.containerCreatedAt &&
      volume.CreatedAt === creation.volumeCreatedAt &&
      network.Created === creation.networkCreatedAt &&
      network.Id === creation.networkId,
    "BOOTSTRAP_CREATION_IDENTITY_MISMATCH",
  );
  check(
    owned(volume, o, "postgres-data") &&
      owned(network, o, "network") &&
      network.Internal === true,
    "BOOTSTRAP_STORAGE_NETWORK_UNOWNED",
  );
  check(
    volume.Driver === "local" &&
      volume.Driver === creation.volumeDriver &&
      Object.keys(volume.Options ?? {}).length === 0 &&
      network.Driver === "bridge" &&
      network.Driver === creation.networkDriver,
    "BOOTSTRAP_STORAGE_NETWORK_DRIVER_REJECTED",
  );
  check(
    Object.values(container.HostConfig.PortBindings ?? {}).every(
      (v) => !v?.length,
    ),
    "BOOTSTRAP_PG_HOST_PUBLICATION_FORBIDDEN",
  );
  check(
    Object.keys(container.NetworkSettings.Networks).length === 1 &&
      container.NetworkSettings.Networks[o.databaseNetwork],
    "BOOTSTRAP_EXTRA_NETWORK_FORBIDDEN",
  );
  check(
    container.Mounts.length === 1 &&
      container.Mounts[0].Type === "volume" &&
      container.Mounts[0].Name === o.databaseVolume &&
      container.Mounts[0].Destination === "/var/lib/postgresql/data",
    "BOOTSTRAP_EXTRA_DATABASE_MOUNT_FORBIDDEN",
  );
  const members = new Set([o.databaseContainerId, ...allowed]);
  check(
    Object.keys(network.Containers ?? {}).every((cid) => members.has(cid)),
    "BOOTSTRAP_FOREIGN_NETWORK_MEMBER",
  );
  const actual = await jsonSql(
    o,
    `SELECT json_build_object('systemIdentifier',(SELECT system_identifier::text FROM pg_control_system()),'version',current_setting('server_version_num')::int);`,
  );
  check(
    actual.systemIdentifier === creation.systemIdentifier &&
      actual.version >= 160000 &&
      actual.version < 170000,
    "BOOTSTRAP_CLUSTER_IDENTITY_MISMATCH",
  );
  return {
    container,
    volume,
    network,
    systemIdentifier: actual.systemIdentifier,
  };
}
async function context(input, operation) {
  const o = validate(input);
  await privateParent(o.bootstrapReceiptPath);
  const lockPath = `${o.bootstrapReceiptPath}.lock`;
  let lock;
  try {
    lock = await open(
      lockPath,
      constants.O_WRONLY |
        constants.O_CREAT |
        constants.O_EXCL |
        constants.O_NOFOLLOW,
      0o600,
    );
  } catch {
    throw new Error("BOOTSTRAP_LOCKED_RECOVERY_REQUIRED");
  }
  try {
    const creation = await privateJson(o.creationReceiptPath);
    check(
      creation.schemaVersion === 1 &&
        creation.emptyVolumeBeforeStart === true &&
        /^[1-9][0-9]{0,19}$/.test(creation.systemIdentifier),
      "BOOTSTRAP_NEW_CLUSTER_RECEIPT_REQUIRED",
    );
    for (const key of [
      "installationId",
      "runtimeInstanceId",
      "databaseContainerId",
      "databaseImageId",
      "databaseVolume",
      "databaseNetwork",
    ])
      check(creation[key] === o[key], "BOOTSTRAP_CREATION_BINDING_MISMATCH");
    const credentials = {};
    for (const key of ["admin", "migration", "service", "login"]) {
      credentials[key] = await privateJson(o[`${key}CredentialsPath`]);
      check(
        typeof credentials[key].password === "string" &&
          /^[\x21-\x7e]{20,256}$/.test(credentials[key].password),
        "BOOTSTRAP_INVALID_CREDENTIAL",
      );
    }
    check(
      new Set(Object.values(credentials).map((c) => c.password)).size === 4,
      "BOOTSTRAP_DISTINCT_CREDENTIALS_REQUIRED",
    );
    const binding = {
      installationId: LOGICAL,
      runtimeInstanceId: o.runtimeInstanceId,
      databaseContainerId: o.databaseContainerId,
      databaseImageId: o.databaseImageId,
      databaseVolume: o.databaseVolume,
      databaseNetwork: o.databaseNetwork,
      systemIdentifier: creation.systemIdentifier,
      creationReceiptSha256: sha(JSON.stringify(creation)),
      bootstrapSourceSha256,
      bootstrapContractSha256,
      roleSourceSha256,
      rolePolicySha256,
      backendProductCommit: BASELINE,
    };
    const journal = await privateJson(o.bootstrapReceiptPath, true);
    if (journal)
      check(
        JSON.stringify(journal.binding) === JSON.stringify(binding),
        "BOOTSTRAP_RECEIPT_BINDING_MISMATCH",
      );
    if (journal?.roleProof) {
      await verifyFrontendIdentity(o, journal);
      check(
        typeof journal.frontendAclSha256 === "string" &&
          (await frontendAclFingerprint(o)) === journal.frontendAclSha256,
        "BOOTSTRAP_ACL_DRIFT_RECONCILIATION_REQUIRED",
      );
    }
    return await operation({ o, creation, credentials, binding, journal });
  } finally {
    await lock.close();
    await unlink(lockPath);
  }
}
function proof(journal) {
  return {
    schemaVersion: 1,
    phase: journal.phase,
    ...journal.binding,
    identity: journal.identity,
    ...(journal.sources ?? {}),
    ...(journal.migrations ? { migrations: journal.migrations } : {}),
    ...(journal.roleProof ? { roleProof: journal.roleProof } : {}),
  };
}
function scram(password) {
  const salt = randomBytes(16),
    salted = pbkdf2Sync(password, salt, 4096, 32, "sha256"),
    client = createHmac("sha256", salted).update("Client Key").digest();
  return `SCRAM-SHA-256$4096:${salt.toString("base64")}$${createHash("sha256").update(client).digest("base64")}:${createHmac("sha256", salted).update("Server Key").digest("base64")}`;
}
function hba(sources = []) {
  return [
    "# Owned stand bootstrap; exact role/source binding only",
    "local all postgres peer",
    `local ${DB} ${MIGRATION},${SERVICE},${LOGIN} scram-sha-256`,
    "local all all reject",
    ...sources.map((s) => `host ${DB} ${s.role} ${s.address}/32 scram-sha-256`),
    "host all all 0.0.0.0/0 reject",
    "host all all ::/0 reject",
    "",
  ].join("\n");
}
async function installHba(o, bytes) {
  await runDocker(
    [
      "exec",
      "--user",
      "postgres",
      "-i",
      o.databaseContainerId,
      "sh",
      "-c",
      'umask 077; file=$(mktemp /var/lib/postgresql/data/.bootstrap-hba.XXXXXXXX); cat > "$file"; chmod 600 "$file"; mv "$file" /var/lib/postgresql/data/pg_hba.conf',
    ],
    bytes,
  );
  check(
    (await sql(o, "SELECT pg_reload_conf()")) === "t",
    "BOOTSTRAP_HBA_RELOAD_REJECTED",
  );
  const rules = await jsonSql(
    o,
    "SELECT json_agg(json_build_object('error',error)) FROM pg_hba_file_rules",
  );
  check(
    rules?.length && rules.every((r) => r.error === null),
    "BOOTSTRAP_HBA_PARSE_REJECTED",
  );
  check(
    sha(
      await runDocker([
        "exec",
        "--user",
        "postgres",
        o.databaseContainerId,
        "cat",
        "/var/lib/postgresql/data/pg_hba.conf",
      ]),
    ) === sha(bytes.trimEnd()),
    "BOOTSTRAP_HBA_HASH_MISMATCH",
  );
}
async function databaseIdentity(o, creation) {
  const result = await jsonSql(
    o,
    `SELECT json_build_object('schemaVersion',1,'installationId',${q(LOGICAL)},'database',current_database(),'databaseOid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),'systemIdentifier',(SELECT system_identifier::text FROM pg_control_system()),'migrationRole',${q(MIGRATION)},'serviceRole',${q(SERVICE)},'loginRole',${q(LOGIN)},'owner',(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()),'comment',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()));`,
    DB,
  );
  check(
    result.systemIdentifier === creation.systemIdentifier &&
      result.owner === MIGRATION &&
      result.comment === `ultistats-stand:${LOGICAL}`,
    "BOOTSTRAP_DATABASE_IDENTITY_MISMATCH",
  );
  const { owner, comment, ...identity } = result;
  return identity;
}
// This is an immutable baseline comparison, not another permission policy.
// Only the existing169 reconciler decides/grants permissions. Once it succeeds,
// capture metadata that can affect this NOINHERIT, membership-free login (PUBLIC
// plus its own grants). Replays are read-only and refuse any such ACL delta.
async function frontendAclFingerprint(o) {
  const grants = await jsonSql(
    o,
    `WITH subject AS (SELECT oid FROM pg_roles WHERE rolname=${q(LOGIN)}), entries AS (
    SELECT jsonb_build_array('relation',c.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable) entry
      FROM pg_class c CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault(CASE WHEN c.relkind='S' THEN 'S'::"char" ELSE 'r'::"char" END,c.relowner))) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('column',c.attrelid::text,c.attnum,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_attribute c CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(c.attacl) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('routine',p.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_proc p CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('schema',n.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_namespace n CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(COALESCE(n.nspacl,acldefault('n',n.nspowner))) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('database',d.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_database d CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(COALESCE(d.datacl,acldefault('d',d.datdba))) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('default',d.defaclrole::text,d.defaclnamespace::text,d.defaclobjtype,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_default_acl d CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(d.defaclacl) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('parameter',p.parname,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_parameter_acl p CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(p.paracl) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('largeobject',l.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_largeobject_metadata l CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(l.lomacl) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('server',f.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_foreign_server f CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(f.srvacl) a WHERE a.grantee IN (0,s.oid)
    UNION ALL SELECT jsonb_build_array('wrapper',f.oid::text,a.grantee::text,a.grantor::text,a.privilege_type,a.is_grantable)
      FROM pg_foreign_data_wrapper f CROSS JOIN subject s CROSS JOIN LATERAL aclexplode(f.fdwacl) a WHERE a.grantee IN (0,s.oid)
  ) SELECT coalesce(jsonb_agg(entry ORDER BY entry::text),'[]'::jsonb) FROM entries;`,
    DB,
  );
  return sha(JSON.stringify(grants));
}
async function verifyFrontendIdentity(o, journal) {
  const role = await jsonSql(
    o,
    `SELECT json_build_object('oid',r.oid::text,'marker',shobj_description(r.oid,'pg_authid'),'safe',NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolinherit AND NOT r.rolreplication AND NOT r.rolbypassrls AND r.rolcanlogin,'memberships',(SELECT count(*) FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid),'owned',(SELECT count(*) FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=r.oid AND deptype='o')) FROM pg_roles r WHERE r.rolname=${q(LOGIN)};`,
  );
  check(
    role &&
      role.oid === journal.frontendRoleOid &&
      role.safe &&
      role.memberships === 0 &&
      role.owned === 0 &&
      role.marker ===
        `ultistats-stand:${LOGICAL}:${journal.identity.systemIdentifier}:${journal.identity.databaseOid}`,
    "BOOTSTRAP_FRONTEND_IDENTITY_MISMATCH",
  );
}
async function verifyRoles(o, creation, expected) {
  const roles = await jsonSql(
    o,
    `SELECT coalesce(json_agg(json_build_object('name',r.rolname,'oid',r.oid::text,'marker',shobj_description(r.oid,'pg_authid'),'safe',NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolinherit AND NOT r.rolreplication AND NOT r.rolbypassrls AND r.rolcanlogin,'memberships',(SELECT count(*) FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)) ORDER BY r.rolname),'[]'::json) FROM pg_roles r WHERE r.rolname IN (${q(MIGRATION)},${q(SERVICE)});`,
  );
  check(
    roles.length === 2 &&
      roles.every(
        (r) =>
          r.safe &&
          r.memberships === 0 &&
          r.marker === marker(o, creation.systemIdentifier),
      ),
    "BOOTSTRAP_ROLE_IDENTITY_MISMATCH",
  );
  const actual = Object.fromEntries(roles.map((r) => [r.name, r.oid]));
  if (expected)
    check(
      JSON.stringify(actual) === JSON.stringify(expected),
      "BOOTSTRAP_ROLE_IDENTITY_MISMATCH",
    );
  return actual;
}
export async function prepareSqlCluster(input) {
  return context(input, async (c) => {
    const { o, creation, credentials, binding } = c;
    let journal = c.journal;
    await observe(
      o,
      creation,
      journal?.sources
        ? [
            journal.sources.proxyBinding.containerId,
            ...journal.sources.writerContainerIds,
          ]
        : [],
    );
    if (journal) {
      check(
        journal.phase !== "NEW_CLUSTER_VERIFIED",
        "BOOTSTRAP_PARTIAL_STATE_REQUIRES_RECOVERY",
      );
      await verifyRoles(o, creation, journal.roleOids);
      check(
        JSON.stringify(await databaseIdentity(o, creation)) ===
          JSON.stringify(journal.identity),
        "BOOTSTRAP_DATABASE_RECEIPT_MISMATCH",
      );
      return proof(journal);
    }
    const fresh = await jsonSql(
      o,
      `SELECT json_build_object('roles',(SELECT count(*) FROM pg_roles WHERE rolname<>'postgres' AND rolname NOT LIKE 'pg\\_%' ESCAPE '\\'),'databases',(SELECT count(*) FROM pg_database WHERE datname NOT IN ('postgres','template0','template1')),'objects',(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'),'extensions',(SELECT count(*) FROM pg_extension WHERE extname<>'plpgsql'),'clients',(SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid()));`,
    );
    check(
      Object.values(fresh).every((v) => v === 0),
      "BOOTSTRAP_EXISTING_CLUSTER_REFUSED",
    );
    journal = {
      schemaVersion: 1,
      binding,
      phase: "NEW_CLUSTER_VERIFIED",
      step: "verified",
      createdAt: new Date().toISOString(),
    };
    await persist(o.bootstrapReceiptPath, journal);
    await sql(
      o,
      `BEGIN; SET LOCAL lock_timeout='5s'; SELECT pg_advisory_xact_lock(169,hashtext(${q(LOGICAL)}));
        DO $guard$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname<>'postgres' AND rolname NOT LIKE 'pg\\_%' ESCAPE '\\') OR EXISTS(SELECT 1 FROM pg_database WHERE datname NOT IN ('postgres','template0','template1')) THEN RAISE EXCEPTION 'Existing cluster'; END IF; END $guard$;
        CREATE ROLE ${id(MIGRATION)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD ${q(scram(credentials.migration.password))};
        CREATE ROLE ${id(SERVICE)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD ${q(scram(credentials.service.password))};
        COMMENT ON ROLE ${id(MIGRATION)} IS ${q(marker(o, creation.systemIdentifier))}; COMMENT ON ROLE ${id(SERVICE)} IS ${q(marker(o, creation.systemIdentifier))};
        REVOKE ALL ON DATABASE postgres,template0,template1 FROM PUBLIC; COMMIT;`,
    );
    journal.step = "roles_created";
    await persist(o.bootstrapReceiptPath, journal);
    journal.step = "database_create_pending";
    await persist(o.bootstrapReceiptPath, journal);
    await sql(
      o,
      `CREATE DATABASE ${id(DB)} OWNER ${id(MIGRATION)} TEMPLATE template0;`,
    );
    journal.step = "database_created";
    await persist(o.bootstrapReceiptPath, journal);
    await sql(
      o,
      `BEGIN; COMMENT ON DATABASE ${id(DB)} IS ${q(`ultistats-stand:${LOGICAL}`)};
        REVOKE ALL ON DATABASE ${id(DB)} FROM PUBLIC; GRANT CONNECT ON DATABASE ${id(DB)} TO ${id(MIGRATION)},${id(SERVICE)};
        ALTER SCHEMA public OWNER TO ${id(MIGRATION)}; REVOKE ALL ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO ${id(SERVICE)};
        ALTER DEFAULT PRIVILEGES FOR ROLE ${id(MIGRATION)} IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO ${id(SERVICE)};
        ALTER DEFAULT PRIVILEGES FOR ROLE ${id(MIGRATION)} IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO ${id(SERVICE)};
        ALTER DEFAULT PRIVILEGES FOR ROLE ${id(MIGRATION)} REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC; COMMIT;`,
      DB,
    );
    await installHba(o, hba());
    journal.roleOids = await verifyRoles(o, creation);
    journal.identity = await databaseIdentity(o, creation);
    journal.phase = "BOOTSTRAP_PREREQUISITES_APPLIED";
    journal.step = "prepared";
    journal.initialHbaSha256 = sha(hba());
    await persist(o.bootstrapReceiptPath, journal);
    return proof(journal);
  });
}
async function sources(c, request) {
  const { o, creation } = c;
  check(
    request &&
      HASH.test(request.proxyContainerId) &&
      IMAGE.test(request.proxyImageId) &&
      Array.isArray(request.writerContainerIds) &&
      request.writerContainerIds.length > 0 &&
      request.writerContainerIds.every((v) => HASH.test(v)),
    "BOOTSTRAP_SOURCE_BINDING_INVALID",
  );
  const ids = [request.proxyContainerId, ...request.writerContainerIds];
  check(
    new Set(ids).size === ids.length && !ids.includes(o.databaseContainerId),
    "BOOTSTRAP_SOURCE_IDENTITIES_OVERLAP",
  );
  const observed = await observe(o, creation, ids),
    proxy = await inspect("container", request.proxyContainerId),
    writers = await Promise.all(
      request.writerContainerIds.map((cid) => inspect("container", cid)),
    );
  check(
    owned(proxy, o, "proxy") &&
      proxy.Image === request.proxyImageId &&
      proxy.State?.Running,
    "BOOTSTRAP_PROXY_UNOWNED",
  );
  check(
    writers.every((w) => owned(w, o, "writer") && w.State?.Running),
    "BOOTSTRAP_WRITER_UNOWNED",
  );
  const address = (c) => {
    check(
      Object.keys(c.NetworkSettings.Networks).length === 1 &&
        c.NetworkSettings.Networks[o.databaseNetwork],
      "BOOTSTRAP_SOURCE_NETWORK_MISMATCH",
    );
    const value = c.NetworkSettings.Networks[o.databaseNetwork].IPAddress;
    check(
      typeof value === "string" &&
        /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(value),
      "BOOTSTRAP_EXACT_IPV4_SOURCE_REQUIRED",
    );
    return value;
  };
  const proxyBinding = {
    containerId: proxy.Id,
    imageId: proxy.Image,
    address: address(proxy),
  };
  const writerBindings = writers.flatMap((w) =>
    [MIGRATION, SERVICE].map((role) => ({
      containerId: w.Id,
      imageId: w.Image,
      address: address(w),
      role,
    })),
  );
  const serviceSources = writerBindings.map(({ role, address }) => ({
    role,
    address,
  }));
  const bytes = hba([
    { role: LOGIN, address: proxyBinding.address },
    ...serviceSources,
  ]);
  return {
    proxyBinding,
    writerBindings,
    writerContainerIds: [...request.writerContainerIds],
    serviceSources,
    hbaSha256: sha(bytes),
    hbaBytes: bytes,
  };
}
export async function bindSqlSources(input, request) {
  return context(input, async (c) => {
    const journal = c.journal;
    check(journal?.identity, "BOOTSTRAP_PREPARE_REQUIRED");
    await verifyRoles(c.o, c.creation, journal.roleOids);
    check(
      JSON.stringify(await databaseIdentity(c.o, c.creation)) ===
        JSON.stringify(journal.identity),
      "BOOTSTRAP_DATABASE_RECEIPT_MISMATCH",
    );
    const bound = await sources(c, request),
      { hbaBytes, ...receipt } = bound;
    if (journal.sources) {
      check(
        JSON.stringify(receipt) === JSON.stringify(journal.sources),
        "BOOTSTRAP_REBIND_REQUIRES_RECOVERY",
      );
      check(
        sha(
          (await runDocker([
            "exec",
            "--user",
            "postgres",
            c.o.databaseContainerId,
            "cat",
            "/var/lib/postgresql/data/pg_hba.conf",
          ])) + "\n",
        ) === receipt.hbaSha256,
        "BOOTSTRAP_HBA_CHANGED",
      );
      return proof(journal);
    }
    check(
      journal.phase === "BOOTSTRAP_PREREQUISITES_APPLIED",
      "BOOTSTRAP_PHASE_INVALID",
    );
    await installHba(c.o, hbaBytes);
    journal.sources = receipt;
    journal.phase = "SQL_SOURCES_BOUND";
    await persist(c.o.bootstrapReceiptPath, journal);
    return proof(journal);
  });
}
export async function finalizeSqlCluster(input, request) {
  return context(input, async (c) => {
    const { o, creation, credentials } = c,
      journal = c.journal;
    check(journal?.sources, "BOOTSTRAP_BIND_REQUIRED");
    await verifyRoles(o, creation, journal.roleOids);
    const bound = await sources(c, request),
      { hbaBytes, ...sourceReceipt } = bound;
    check(
      JSON.stringify(sourceReceipt) === JSON.stringify(journal.sources),
      "BOOTSTRAP_SOURCE_RECEIPT_MISMATCH",
    );
    check(
      sha(
        (await runDocker([
          "exec",
          "--user",
          "postgres",
          o.databaseContainerId,
          "cat",
          "/var/lib/postgresql/data/pg_hba.conf",
        ])) + "\n",
      ) === bound.hbaSha256,
      "BOOTSTRAP_HBA_CHANGED",
    );
    check(
      JSON.stringify(await databaseIdentity(o, creation)) ===
        JSON.stringify(journal.identity),
      "BOOTSTRAP_DATABASE_RECEIPT_MISMATCH",
    );
    const app = request.application;
    check(
      app &&
        HASH.test(app.containerId) &&
        app.sourceCommit === BASELINE &&
        HASH.test(app.jarSha256) &&
        app.jarPath === "/app/app.jar" &&
        request.writerContainerIds.includes(app.containerId),
      "BOOTSTRAP_BASELINE_ARTIFACT_REQUIRED",
    );
    const container = await inspect("container", app.containerId),
      env = Object.fromEntries(
        container.Config.Env.map((v) => {
          const i = v.indexOf("=");
          return [v.slice(0, i), v.slice(i + 1)];
        }),
      );
    check(
      container.Config.Labels?.["org.opencontainers.image.revision"] ===
        BASELINE &&
        env.DATABASE_USERNAME === SERVICE &&
        env.SPRING_FLYWAY_USER === MIGRATION &&
        env.DATABASE_PASSWORD === credentials.service.password &&
        env.SPRING_FLYWAY_PASSWORD === credentials.migration.password,
      "BOOTSTRAP_APPLICATION_IDENTITY_MISMATCH",
    );
    check(
      (
        await runDocker(["exec", app.containerId, "sha256sum", app.jarPath])
      ).split(/\s/)[0] === app.jarSha256,
      "BOOTSTRAP_ARTIFACT_HASH_MISMATCH",
    );
    const migrations = await jsonSql(
      o,
      `SELECT json_agg(json_build_object('version',version,'script',script,'installedBy',installed_by,'success',success) ORDER BY installed_rank) FROM public.flyway_schema_history;`,
      DB,
    );
    const expected = ["1", "1.1", "2", "3", "4", "5", "6", "7", "8", "9"];
    check(
      migrations?.length === 10 &&
        migrations.every(
          (m, i) =>
            m.version === expected[i] &&
            m.installedBy === MIGRATION &&
            m.success === true,
        ),
      "BOOTSTRAP_ACTUAL247_OWNER_MIGRATIONS_REQUIRED",
    );
    const appWriter = bound.writerBindings.find(
      (w) => w.containerId === app.containerId && w.role === SERVICE,
    );
    check(
      (await sql(
        o,
        `SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=${q(DB)} AND usename=${q(SERVICE)} AND host(client_addr)=${q(appWriter.address)})`,
        DB,
      )) === "t",
      "BOOTSTRAP_SERVICE_POOL_UNPROVEN",
    );
    if (journal.phase === "SQL_INGRESS_READY_FOR170") return proof(journal);
    check(
      journal.phase === "SQL_SOURCES_BOUND",
      "BOOTSTRAP_PARTIAL_FINALIZATION_REQUIRES_RECOVERY",
    );
    journal.phase = "BACKEND247_MIGRATED_AS_MIGRATION_OWNER";
    journal.migrations = migrations;
    journal.applicationArtifact = { ...app };
    await persist(o.bootstrapReceiptPath, journal);
    await sql(
      o,
      `BEGIN; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO ${id(SERVICE)}; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO ${id(SERVICE)}; REVOKE ALL ON TABLE public.flyway_schema_history FROM ${id(SERVICE)}; SET LOCAL ROLE ${id(SERVICE)};
        INSERT INTO public.teams(id,name) VALUES (${q(randomUUID())}::uuid,'bootstrap-validation');
        UPDATE public.teams SET city='validated' WHERE name='bootstrap-validation'; SELECT count(*) FROM public.teams WHERE name='bootstrap-validation'; DELETE FROM public.teams WHERE name='bootstrap-validation'; ROLLBACK;`,
      DB,
    );
    // Persist grants separately: the DML probe above intentionally rolls back.
    await sql(
      o,
      `BEGIN; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO ${id(SERVICE)}; GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO ${id(SERVICE)}; REVOKE ALL ON TABLE public.flyway_schema_history FROM ${id(SERVICE)}; COMMIT;`,
      DB,
    );
    journal.phase = "SERVICE_DML_VERIFIED";
    await persist(o.bootstrapReceiptPath, journal);
    const adapterDirectory = await mkdtemp(
      join(dirname(o.bootstrapReceiptPath), ".bootstrap-role-"),
    );
    try {
      const adapter = join(adapterDirectory, "psql");
      await writeFile(
        adapter,
        `#!/bin/sh\nexec docker exec --user postgres -i ${o.databaseContainerId} psql "$@"\n`,
        { mode: 0o700 },
      );
      const adminPath = join(adapterDirectory, "admin.json");
      await writeFile(
        adminPath,
        JSON.stringify({
          host: "/var/run/postgresql",
          port: 5432,
          database: DB,
          user: "postgres",
          password: credentials.admin.password,
          sslmode: "disable",
        }),
        { mode: 0o600 },
      );
      journal.roleProof = await reconcileSqlRole({
        identity: journal.identity,
        adminCredentialsPath: adminPath,
        loginCredentialsPath: o.loginCredentialsPath,
        psqlPath: adapter,
      });
    } finally {
      await rm(adapterDirectory, { recursive: true, force: true });
    }
    await sql(o, `REVOKE TEMP ON DATABASE ${id(DB)} FROM ${id(SERVICE)};`, DB);
    journal.frontendRoleOid = await sql(
      o,
      `SELECT oid::text FROM pg_roles WHERE rolname=${q(LOGIN)}`,
    );
    await verifyFrontendIdentity(o, journal);
    journal.frontendAclSha256 = await frontendAclFingerprint(o);
    journal.phase = "ROLE169_RECONCILED";
    await persist(o.bootstrapReceiptPath, journal);
    check(
      sha(
        (await runDocker([
          "exec",
          "--user",
          "postgres",
          o.databaseContainerId,
          "cat",
          "/var/lib/postgresql/data/pg_hba.conf",
        ])) + "\n",
      ) === bound.hbaSha256,
      "BOOTSTRAP_HBA_CHANGED",
    );
    journal.phase = "SQL_INGRESS_READY_FOR170";
    journal.completedAt = new Date().toISOString();
    await persist(o.bootstrapReceiptPath, journal);
    return proof(journal);
  });
}
