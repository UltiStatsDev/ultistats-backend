import test from "node:test";
import assert from "node:assert/strict";
const api = await import("../backend-release.mjs").catch(() => ({}));
const fixed = {
  instanceId: "abc123456789",
  databaseContainerId: "1".repeat(64),
  databaseVolume: "owned-pg",
  uploadsVolume: "owned-up",
  origin: "http://127.0.0.1:25461",
};
function receipt() {
  return {
    protocolVersion: 1,
    transactionId: "transaction-001",
    targetRecordSha256: "a".repeat(64),
    manifestSha256: "b".repeat(64),
    identity: fixed,
    current: { version: "0.0.0", web: "c".repeat(40), backend: "d".repeat(40) },
  };
}
test("stale/replayed/foreign fence cannot authorize backup", () => {
  const r = receipt(),
    request = {
      transactionId: r.transactionId,
      targetRecordSha256: r.targetRecordSha256,
    };
  assert.equal(api.verifyFence(r, request, "b".repeat(64), fixed), true);
  for (const changed of [
    { ...r, transactionId: "other-transaction" },
    { ...r, targetRecordSha256: "f".repeat(64) },
    { ...r, manifestSha256: "e".repeat(64) },
    { ...r, identity: { ...fixed, databaseVolume: "foreign" } },
  ])
    assert.throws(
      () => api.verifyFence(changed, request, "b".repeat(64), fixed),
      { message: "FENCE_MISMATCH" },
    );
});
test("target manifest may change writer identity but cannot change persistent installation", () => {
  const r = receipt(),
    request = {
      transactionId: r.transactionId,
      targetRecordSha256: r.targetRecordSha256,
    };
  assert.equal(
    api.verifyFence(r, request, "different-target-manifest", fixed, {
      target: true,
    }),
    true,
  );
  assert.throws(
    () =>
      api.verifyFence(
        r,
        request,
        "different-target-manifest",
        { ...fixed, origin: "http://other" },
        { target: true },
      ),
    { message: "FENCE_MISMATCH" },
  );
});
test("postflight normal target never accepts old pair without proven rollback receipt", () => {
  const record = {
    version: "0.1.0",
    repositories: {
      web: { commit: "a".repeat(40) },
      backend: { commit: "b".repeat(40) },
    },
  };
  assert.deepEqual(api.desiredPair(record), {
    version: "0.1.0",
    web: "a".repeat(40),
    backend: "b".repeat(40),
  });
  assert.throws(() => api.desiredPair(record, { compatible: false }), {
    message: "ROLLBACK_UNPROVEN",
  });
});
