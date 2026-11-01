import test from "node:test";
import assert from "node:assert/strict";
const api = await import("../capacity.mjs").catch(() => ({}));
test("capacity budget requires both destination and temporary free space", () => {
  assert.throws(
    () =>
      api.verifyCapacity({
        databaseBytes: 1000n,
        uploadsArchiveBytes: 2000n,
        settingsBytes: 100n,
        availableBytes: 100n,
        temporaryAvailableBytes: 1000000000n,
      }),
    { message: "BACKUP_SPACE_INSUFFICIENT" },
  );
  assert.throws(
    () =>
      api.verifyCapacity({
        databaseBytes: 1000n,
        uploadsArchiveBytes: 2000n,
        settingsBytes: 100n,
        availableBytes: 1000000000n,
        temporaryAvailableBytes: 100n,
      }),
    { message: "BACKUP_SPACE_INSUFFICIENT" },
  );
  const p = api.verifyCapacity({
    databaseBytes: 1000n,
    uploadsArchiveBytes: 2000n,
    settingsBytes: 100n,
    availableBytes: 1000000000n,
    temporaryAvailableBytes: 1000000000n,
  });
  assert.equal(p.verified, true);
  assert.equal(p.requiredBytes, "67114964");
  assert.equal(p.databaseBytes, "1000");
});
test("unknown or malformed size is never interpreted as available backup capacity", () => {
  for (const databaseBytes of [undefined, -1n, 0n, "100", NaN])
    assert.throws(
      () =>
        api.verifyCapacity({
          databaseBytes,
          uploadsArchiveBytes: 0n,
          settingsBytes: 0n,
          availableBytes: 1000000000n,
          temporaryAvailableBytes: 1000000000n,
        }),
      { message: "BACKUP_CAPACITY_UNKNOWN" },
    );
});
