import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  rmSync,
  readFileSync,
  writeFileSync,
  symlinkSync,
  unlinkSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { verifySourceIdentity } from "../observe.mjs";
const baseline = "24704e11143a0bf919559a41324603f936ae03cf";
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "backend161-eol-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const repo = join(root, "source");
  const git = (...args) =>
    execFileSync("git", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    }).trim();
  git("clone", "--shared", "--no-checkout", resolve("."), repo);
  git("-C", repo, "checkout", "--detach", baseline);
  const raw = execFileSync(
    "git",
    ["--no-replace-objects", "-C", repo, "show", `${baseline}:gradlew.bat`],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
  const m = {
    instanceId: "abc123456789",
    backend: {
      revision: baseline,
      imageId: `sha256:${"1".repeat(64)}`,
      helper: { sourceCheckout: repo },
    },
  };
  const run = (program, args) =>
    program === "git"
      ? git(...args)
      : JSON.stringify([
          {
            Id: m.backend.imageId,
            Config: {
              Labels: {
                "org.opencontainers.image.revision": m.backend.revision,
                "io.ultistats.instance": m.instanceId,
              },
            },
          },
        ]);
  return { repo, raw, git, check: () => verifySourceIdentity(m, { run }) };
}
test("readonly actual247 canonical LF reports byte identical source", (t) => {
  const f = fixture(t);
  writeFileSync(join(f.repo, "gradlew.bat"), f.raw);
  const proof = f.check();
  assert.equal(proof.byteIdentical, true);
  assert.deepEqual(proof.eolDelta, []);
  assert.equal(proof.sourceSha, baseline);
});
test("readonly actual247 exact tracked CRLF records truthful narrow EOL delta", (t) => {
  const f = fixture(t);
  writeFileSync(
    join(f.repo, "gradlew.bat"),
    f.raw.toString().replaceAll("\n", "\r\n"),
  );
  const p = f.check();
  assert.equal(p.byteIdentical, false);
  assert.equal(p.eolDelta.length, 1);
  assert.equal(p.eolDelta[0].path, "gradlew.bat");
  assert.equal(
    p.eolDelta[0].rawSha256,
    "94102713eb8fb22d032397924c0f38ab2da783ba60d07054339f1190a0c4e2cd",
  );
  assert.notEqual(p.eolDelta[0].actualSha256, p.eolDelta[0].rawSha256);
  assert.equal(p.eolDelta[0].reason, "tracked-attribute-CRLF");
});
test("mixed EOL is never the permitted legacy CRLF representation", (t) => {
  const f = fixture(t);
  writeFileSync(
    join(f.repo, "gradlew.bat"),
    f.raw.toString().replace("\n", "\r\n"),
  );
  assert.throws(f.check, /SOURCE_BYTES_MISMATCH/);
});
test("legacy exception rejects UTF16 and symlink representations", (t) => {
  const f = fixture(t);
  writeFileSync(
    join(f.repo, "gradlew.bat"),
    Buffer.from(f.raw.toString(), "utf16le"),
  );
  assert.throws(f.check, /SOURCE_NOT_CLEAN|SOURCE_BYTES_MISMATCH/);
  unlinkSync(join(f.repo, "gradlew.bat"));
  symlinkSync(join(f.repo, "README.md"), join(f.repo, "gradlew.bat"));
  assert.throws(f.check, /SOURCE_NOT_CLEAN|SOURCE_BYTES_MISMATCH/);
});
test("another tracked path cannot borrow gradlew EOL exception", (t) => {
  const f = fixture(t);
  writeFileSync(
    join(f.repo, ".git/info/attributes"),
    ".gitattributes text eol=crlf\n",
  );
  const p = join(f.repo, ".gitattributes");
  writeFileSync(p, readFileSync(p, "utf8").replaceAll("\n", "\r\n"));
  assert.throws(f.check, /SOURCE_NOT_CLEAN|SOURCE_BYTES_MISMATCH/);
});
test("assume-unchanged index flag cannot hide modified worktree bytes", (t) => {
  const f = fixture(t);
  f.git("-C", f.repo, "update-index", "--assume-unchanged", "README.md");
  writeFileSync(join(f.repo, "README.md"), "unapproved replacement\n");
  assert.throws(f.check, /SOURCE_NOT_CLEAN|SOURCE_BYTES_MISMATCH/);
});
