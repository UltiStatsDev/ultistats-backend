import {
  readdirSync,
  lstatSync,
  readFileSync,
  mkdirSync,
  writeFileSync,
  copyFileSync,
  chmodSync,
  mkdtempSync,
  rmSync,
} from "node:fs";
import { join, dirname } from "node:path";
import { tmpdir } from "node:os";
import {
  hash,
  check,
  privateDirectory,
  writePrivateJson,
  readPrivateJson,
} from "./helper-contract.mjs";
import { command, databaseContent } from "./observe.mjs";
import { inspectBackupCapacity } from "./capacity.mjs";

export function treeFingerprint(root) {
  const entries = [];
  function visit(dir, relative = "") {
    for (const name of readdirSync(dir).sort()) {
      const path = join(dir, name),
        rel = relative ? `${relative}/${name}` : name,
        s = lstatSync(path);
      check(!s.isSymbolicLink(), "UNSUPPORTED_UPLOAD_ENTRY");
      if (s.isDirectory()) {
        entries.push([rel, "directory"]);
        visit(path, rel);
      } else {
        check(s.isFile(), "UNSUPPORTED_UPLOAD_ENTRY");
        entries.push([rel, s.size, hash(readFileSync(path))]);
      }
    }
  }
  visit(root);
  return hash(JSON.stringify(entries));
}
export function uploadsFingerprint(manifest, { run = command } = {}) {
  const temp = mkdtempSync(join(tmpdir(), "ultistats-helper-uploads-"));
  try {
    const destination = join(temp, "uploads");
    run(
      "docker",
      [
        "cp",
        `${manifest.backend.helper.writerContainerIds[0]}:/app/uploads/.`,
        destination,
      ],
      { timeout: 120000 },
    );
    return treeFingerprint(destination);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
}
export function settingsFingerprint(manifest) {
  return manifest.backend.helper.settingsPaths.map((path) => {
    readPrivateJsonIfNeeded(path);
    return { path, sha256: hash(readFileSync(path)) };
  });
}
function readPrivateJsonIfNeeded(path) {
  // Settings may be dotenv/text; enforce the same no-symlink/private ownership boundary.
  const stat = lstatSync(path);
  check(
    stat.isFile() &&
      !stat.isSymbolicLink() &&
      stat.uid === process.getuid?.() &&
      !(stat.mode & 0o077),
    "PRIVATE_FILE_REQUIRED",
  );
}
export async function createBackup({
  backupPath,
  manifest,
  receipt,
  recheck,
  run = command,
  content = databaseContent,
}) {
  privateDirectory(dirname(backupPath));
  await recheck();
  const capacity = inspectBackupCapacity(manifest, backupPath, { run });
  mkdirSync(backupPath, { mode: 0o700 }); // exclusive, never reuse partial/foreign output
  writePrivateJson(join(backupPath, "incomplete.json"), {
    protocolVersion: 1,
    transactionId: receipt.transactionId,
    complete: false,
  });
  const h = manifest.backend.helper;
  const beforeContent = content(manifest, { run });
  const dump = run(
    "docker",
    [
      "exec",
      h.databaseContainerId,
      "sh",
      "-c",
      'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump --format=custom --no-owner --no-acl --no-password -U "$1" -d "$2"',
      "sh",
      h.databaseUser,
      h.databaseName,
    ],
    { encoding: null, timeout: 120000, maxBuffer: 256 * 1024 * 1024 },
  );
  check(
    Buffer.isBuffer(dump) &&
      dump.length > 5 &&
      dump.subarray(0, 5).toString() === "PGDMP",
    "DUMP_INVALID",
  );
  writeFileSync(join(backupPath, "database.dump"), dump, {
    mode: 0o600,
    flag: "wx",
  });
  const listing = run(
    "docker",
    ["exec", "-i", h.databaseContainerId, "pg_restore", "--list"],
    { input: dump, timeout: 30000 },
  );
  check(
    typeof listing === "string" && listing.trim().length > 0,
    "DUMP_INVALID",
  );
  run(
    "docker",
    [
      "cp",
      `${h.writerContainerIds[0]}:/app/uploads/.`,
      join(backupPath, "uploads"),
    ],
    { timeout: 120000 },
  );
  const uploadsSha256 = treeFingerprint(join(backupPath, "uploads"));
  const settings = settingsFingerprint(manifest);
  settings.forEach((s, i) => {
    const path = join(backupPath, `settings-${i}`);
    copyFileSync(s.path, path);
    chmodSync(path, 0o600);
  });
  await recheck();
  check(
    content(manifest, { run }) === beforeContent,
    "DATA_CHANGED_DURING_BACKUP",
  );
  check(
    uploadsFingerprint(manifest, { run }) === uploadsSha256 &&
      JSON.stringify(settingsFingerprint(manifest)) ===
        JSON.stringify(settings),
    "DATA_CHANGED_DURING_BACKUP",
  );
  const complete = {
    protocolVersion: 1,
    transactionId: receipt.transactionId,
    complete: true,
    capacity,
    fence: receipt,
    databaseSha256: hash(dump),
    databaseContentSha256: beforeContent,
    uploadsSha256,
    settings,
  };
  writePrivateJson(join(backupPath, "manifest.json"), complete);
  return complete;
}
export function readBackup(backupPath) {
  privateDirectory(backupPath);
  const b = readPrivateJson(join(backupPath, "manifest.json"));
  check(b.protocolVersion === 1 && b.complete === true, "BACKUP_INCOMPLETE");
  check(
    hash(readFileSync(join(backupPath, "database.dump"))) ===
      b.databaseSha256 &&
      treeFingerprint(join(backupPath, "uploads")) === b.uploadsSha256,
    "BACKUP_CORRUPT",
  );
  check(
    Array.isArray(b.settings) &&
      b.settings.every(
        (s, i) =>
          hash(readFileSync(join(backupPath, `settings-${i}`))) === s.sha256,
      ),
    "BACKUP_CORRUPT",
  );
  return b;
}
