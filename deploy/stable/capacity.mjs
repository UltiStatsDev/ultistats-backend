import { statfsSync, lstatSync, realpathSync } from "node:fs";
import { dirname } from "node:path";
import { tmpdir } from "node:os";
import { check, privateDirectory } from "./helper-contract.mjs";
import { command, sql } from "./observe.mjs";
const mib = 1024n * 1024n;
export function verifyCapacity({
  databaseBytes,
  uploadsArchiveBytes,
  settingsBytes,
  availableBytes,
  temporaryAvailableBytes,
  sharedFilesystem = false,
}) {
  check(
    [
      databaseBytes,
      uploadsArchiveBytes,
      settingsBytes,
      availableBytes,
      temporaryAvailableBytes,
    ].every((v) => typeof v === "bigint" && v >= 0n) && databaseBytes > 0n,
    "BACKUP_CAPACITY_UNKNOWN",
  );
  const temporaryRequiredBytes = 2n * uploadsArchiveBytes + 128n * mib;
  const requiredBytes =
    2n * databaseBytes +
    2n * uploadsArchiveBytes +
    settingsBytes +
    64n * mib +
    (sharedFilesystem ? temporaryRequiredBytes : 0n);
  check(
    availableBytes >= requiredBytes &&
      temporaryAvailableBytes >= temporaryRequiredBytes,
    "BACKUP_SPACE_INSUFFICIENT",
  );
  return {
    verified: true,
    databaseBytes: String(databaseBytes),
    uploadsArchiveBytes: String(uploadsArchiveBytes),
    settingsBytes: String(settingsBytes),
    requiredBytes: String(requiredBytes),
    availableBytes: String(availableBytes),
    temporaryRequiredBytes: String(temporaryRequiredBytes),
    temporaryAvailableBytes: String(temporaryAvailableBytes),
  };
}
export function inspectBackupCapacity(
  manifest,
  backupDirectory,
  { run = command } = {},
) {
  privateDirectory(dirname(backupDirectory));
  const h = manifest.backend.helper;
  const database = sql(
    manifest,
    "SELECT pg_database_size(current_database())::text;",
    { run },
  );
  check(/^[1-9][0-9]*$/.test(database), "BACKUP_CAPACITY_UNKNOWN");
  const archive = run(
    "docker",
    ["cp", `${h.writerContainerIds[0]}:/app/uploads/.`, "-"],
    { encoding: null, timeout: 120000, maxBuffer: 256 * 1024 * 1024 },
  );
  check(
    Buffer.isBuffer(archive) && archive.length >= 512,
    "BACKUP_CAPACITY_UNKNOWN",
  );
  let settingsBytes = 0n;
  for (const path of h.settingsPaths) {
    const stat = lstatSync(path, { bigint: true });
    check(
      stat.isFile() &&
        !stat.isSymbolicLink() &&
        stat.uid === BigInt(process.getuid()) &&
        !(stat.mode & 0o077n) &&
        realpathSync(path) === path,
      "PRIVATE_FILE_REQUIRED",
    );
    settingsBytes += stat.size;
  }
  const destination = dirname(backupDirectory);
  const backupFs = statfsSync(destination, { bigint: true }),
    temporaryFs = statfsSync(tmpdir(), { bigint: true });
  const proof = verifyCapacity({
    databaseBytes: BigInt(database),
    uploadsArchiveBytes: BigInt(archive.length),
    settingsBytes,
    availableBytes: backupFs.bavail * backupFs.bsize,
    temporaryAvailableBytes: temporaryFs.bavail * temporaryFs.bsize,
    sharedFilesystem: lstatSync(destination).dev === lstatSync(tmpdir()).dev,
  });
  return { ...proof, backupDirectory };
}
