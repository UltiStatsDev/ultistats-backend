export function requireProof(condition, reason) {
  if (!condition) throw new Error(reason);
}

// This checks a fresh observation. A stored maintenance receipt is never a fence.
export function verifyIdentity(
  expected,
  observed,
  { allowActiveMatches = false } = {},
) {
  const e = expected,
    o = observed;
  for (const c of [o.database, o.ingress, ...o.writers]) {
    requireProof(
      c.project === e.project && c.instanceId === e.instanceId,
      "OWNERSHIP_MISMATCH",
    );
  }
  requireProof(
    o.database.id === e.databaseContainerId &&
      o.database.volume === e.databaseVolume &&
      o.ingress.id === e.ingressContainerId &&
      o.ingress.imageId === e.ingressImageId &&
      o.writers.length === e.writerContainerIds.length &&
      new Set(o.writers.map((w) => w.id)).size === o.writers.length &&
      e.writerContainerIds.every((id) =>
        o.writers.some(
          (w) =>
            w.id === id &&
            w.imageId === e.imageId &&
            w.uploadsVolume === e.uploadsVolume,
        ),
      ),
    "IDENTITY_MISMATCH",
  );
  requireProof(
    o.database.networks.length === 1 &&
      o.database.networks[0] === e.databaseNetwork &&
      o.database.publishedPorts === 0 &&
      o.network.internal === true &&
      o.network.name === e.databaseNetwork &&
      o.writers.every(
        (w) =>
          w.publishedPorts === 0 &&
          w.networks.length === 1 &&
          w.networks[0] === e.databaseNetwork,
      ),
    "NETWORK_NOT_ISOLATED",
  );
  const allowed = new Set([
    e.databaseContainerId,
    e.ingressContainerId,
    ...e.writerContainerIds,
  ]);
  requireProof(
    o.network.containerIds.every((id) => allowed.has(id)),
    "UNKNOWN_NETWORK_CLIENT",
  );
  requireProof(
    o.database.running === true &&
      o.databaseReady === true &&
      Number.isSafeInteger(o.activeMatches) &&
      o.activeMatches >= 0 &&
      Number.isSafeInteger(o.otherClients) &&
      o.otherClients >= 0,
    "DATABASE_UNREADY",
  );
  requireProof(
    allowActiveMatches || o.activeMatches === 0,
    "MATCH_IN_PROGRESS",
  );
  requireProof(o.migrationsVerified === true, "MIGRATION_UNPROVEN");
  requireProof(
    o.sourceVerified === true && o.jarVerified === true,
    "RUNTIME_UNPROVEN",
  );
  return "ok";
}

export function verifyQuietPoint(expected, observed) {
  verifyIdentity(expected, observed);
  requireProof(
    observed.writers.every((w) => w.running === false),
    "WRITER_RUNNING",
  );
  requireProof(observed.otherClients === 0, "UNKNOWN_DATABASE_CLIENT");
  requireProof(
    observed.ingress.maintenance === true &&
      observed.ingress.configVerified === true,
    "INGRESS_NOT_FENCED",
  );
  return "ok";
}

export function rollbackCompatible(
  previous,
  compatibility,
  actualProductAndSchemaVerified,
) {
  return (
    actualProductAndSchemaVerified === true &&
    compatibility?.fromSchema === 9 &&
    compatibility?.toSchema === 9 &&
    compatibility?.appRollback === true &&
    Array.isArray(compatibility.supportedSourcePairs) &&
    compatibility.supportedSourcePairs.some(
      (p) => p.web === previous.web && p.backend === previous.backend,
    )
  );
}
