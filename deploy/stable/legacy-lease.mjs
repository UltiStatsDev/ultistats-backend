import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { check } from "./helper-contract.mjs";
import { command, sql } from "./observe.mjs";
export async function acquireLegacyLease(
  manifest,
  {
    transactionId,
    run = command,
    spawnProcess = spawn,
    timeoutMs = 10000,
    maxLeaseMs = 180000,
  } = {},
) {
  const h = manifest.backend.helper;
  check(
    /^[A-Za-z0-9-]{8,80}$/.test(transactionId ?? "") &&
      /^[a-f0-9]{64}$/.test(h.databaseContainerId) &&
      /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(h.databaseName) &&
      /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(h.databaseUser) &&
      Number.isSafeInteger(maxLeaseMs) &&
      maxLeaseMs > 0 &&
      maxLeaseMs <= 180000,
    "INVALID_LEGACY_LEASE",
  );
  const tables = JSON.parse(
    sql(
      manifest,
      "SELECT coalesce(json_agg(tablename ORDER BY tablename),'[]') FROM pg_tables WHERE schemaname='public';",
      { run },
    ),
  );
  check(
    Array.isArray(tables) &&
      tables.length > 0 &&
      tables.length <= 64 &&
      tables.every((t) => /^[a-z_][a-z0-9_]*$/.test(t)),
    "UNKNOWN_DATABASE_LAYOUT",
  );
  const id = randomBytes(12).toString("hex"),
    applicationName = `ultistats-legacy-${id}`,
    expiresAt = Date.now() + maxLeaseMs;
  const child = spawnProcess(
    "docker",
    [
      "exec",
      "-i",
      h.databaseContainerId,
      "sh",
      "-c",
      'exec /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin PGPASSWORD="$POSTGRES_PASSWORD" PGAPPNAME="$3" psql -h 127.0.0.1 -p 5432 -X -qAt --no-password --set=ON_ERROR_STOP=1 -U "$1" -d "$2"',
      "sh",
      h.databaseUser,
      h.databaseName,
      applicationName,
    ],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  let released = false,
    exited = false,
    expired = false,
    invalid = false,
    buffer = "",
    stderr = "",
    backendPid,
    backendStart,
    lastRecheck = 0,
    resolveReady,
    rejectReady;
  const ready = new Promise((resolve, reject) => {
    resolveReady = resolve;
    rejectReady = reject;
  });
  let finishExit;
  const exitPromise = new Promise((resolve) => {
    finishExit = resolve;
  });
  function lost() {
    invalid = true;
    const reject = rejectReady;
    rejectReady = undefined;
    resolveReady = undefined;
    reject?.(
      new Error(
        stderr.includes("MATCH_IN_PROGRESS")
          ? "MATCH_IN_PROGRESS"
          : "LEGACY_FENCE_LOST",
      ),
    );
  }
  child.on("exit", () => {
    exited = true;
    finishExit();
    lost();
  });
  child.on("error", () => {
    exited = true;
    finishExit();
    lost();
  });
  child.stdin.on?.("error", lost);
  child.stderr.on("data", (data) => {
    stderr = (stderr + data.toString()).slice(-4096);
  });
  child.stdout.on("data", (data) => {
    buffer += data.toString();
    if (buffer.length > 16384) {
      lost();
      return;
    }
    let newline;
    while ((newline = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line === `LEGACY_ACTIVE_${id}`) {
        stderr = "MATCH_IN_PROGRESS";
        lost();
        continue;
      }
      const match = new RegExp(
        `^LEGACY_LOCKED_${id}\\|([0-9]+)\\|([0-9]+(?:\\.[0-9]+)?)$`,
      ).exec(line);
      if (match && resolveReady) {
        backendPid = Number(match[1]);
        backendStart = match[2];
        const resolve = resolveReady;
        resolveReady = undefined;
        rejectReady = undefined;
        resolve();
      }
    }
  });
  const expire = setTimeout(() => {
    expired = true;
    try {
      child.stdin.end("ROLLBACK;\n");
    } catch {}
    lost();
  }, maxLeaseMs);
  expire.unref?.();
  async function release() {
    if (released) return;
    released = true;
    clearTimeout(expire);
    if (!exited) {
      try {
        child.stdin.end("ROLLBACK;\n");
      } catch {}
      const timeout = new Promise((resolve) => {
        const timer = setTimeout(resolve, 2000);
        timer.unref?.();
      });
      await Promise.race([exitPromise, timeout]);
      if (!exited) child.kill("SIGTERM");
    }
  }
  async function recheck() {
    check(
      !released &&
        !exited &&
        !expired &&
        !invalid &&
        Date.now() < expiresAt &&
        Number.isSafeInteger(backendPid) &&
        backendPid > 0,
      "LEGACY_FENCE_LOST",
    );
    const relations = tables.map((t) => `'public."${t}"'::regclass`).join(",");
    const proof = JSON.parse(
      sql(
        manifest,
        `SELECT json_build_object('session',EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=${backendPid} AND application_name='${applicationName}' AND extract(epoch from backend_start)=${backendStart} AND xact_start IS NOT NULL),'locks',(SELECT count(DISTINCT relation) FROM pg_locks WHERE pid=${backendPid} AND mode='ShareLock' AND granted AND relation IN (${relations})));`,
        { run },
      ),
    );
    check(
      proof.session === true &&
        proof.locks === tables.length &&
        !exited &&
        !released &&
        !expired &&
        !invalid &&
        Date.now() < expiresAt,
      "LEGACY_FENCE_LOST",
    );
    lastRecheck = Date.now();
    return { id, backendPid, expiresAt };
  }
  async function confirmDatabaseStopped() {
    check(
      !released &&
        !expired &&
        Date.now() < expiresAt &&
        lastRecheck > 0 &&
        Date.now() - lastRecheck <= 20000,
      "LEGACY_FENCE_LOST",
    );
    const db = JSON.parse(run("docker", ["inspect", h.databaseContainerId]))[0];
    check(
      db?.Id === h.databaseContainerId &&
        db.Config?.Labels?.["com.docker.compose.project"] ===
          manifest.backend.project,
      "LEGACY_OWNERSHIP_UNPROVEN",
    );
    check(db.State?.Running === false, "LEGACY_DATABASE_STILL_RUNNING");
    const proof = {
      id,
      stopped: true,
      databaseContainerId: db.Id,
      databaseImageId: db.Image,
    };
    await release();
    return proof;
  }
  const timeout = setTimeout(() => lost(), timeoutMs);
  timeout.unref?.();
  try {
    const names = tables.map((t) => `public."${t}"`).join(",");
    child.stdin.write(
      `BEGIN;\nSET LOCAL lock_timeout='5s';\nSET LOCAL statement_timeout='10s';\nSET LOCAL idle_in_transaction_session_timeout='${maxLeaseMs}ms';\nLOCK TABLE ${names} IN SHARE MODE;\nSELECT CASE WHEN EXISTS(SELECT 1 FROM public.matches WHERE started_at IS NOT NULL AND ended_at IS NULL) THEN 'LEGACY_ACTIVE_${id}' ELSE 'LEGACY_IDLE_${id}' END;\nSELECT 'LEGACY_LOCKED_${id}|'||pg_backend_pid()::text||'|'||(SELECT extract(epoch from backend_start)::text FROM pg_stat_activity WHERE pid=pg_backend_pid());\n`,
    );
    await ready;
    clearTimeout(timeout);
    await recheck();
    return {
      id,
      backendPid,
      recheck,
      release,
      confirmDatabaseStopped,
      toJSON() {
        throw new Error("LEGACY_FENCE_HANDLE_NOT_SERIALIZABLE");
      },
    };
  } catch (error) {
    clearTimeout(timeout);
    await release();
    throw error;
  }
}
