# Backend176 — typed owned candidate producer

Base: bbd1f1b8b70699d1043376b63941d8c1f6e43e5c, tree d60af55aa85644713de9520356a1e58df1d2afc5. Refs #176 and #159. One independently callable producer; no runtime authority or unmerged consumer dependency.

Frozen design SHA256 b244397956e88ed55e4492fd383a382f8427bbc7d2e8174fbb1e2cfea9bad143; artifact ABI fda148e1beccb3a519321a739651e001cc274db6f6b59b19ae22b7f53c7ebb76. Exact schema/phase fields follow those artifacts; selectors and echoed hashes are not authority.

## Files and interfaces

- New `deploy/stable/candidate-target.mjs`: sole `validateCandidateDescriptor(value)` / `validateCandidateRequest(value)`; private-stage loader verifies raw snapshot/build-result/tool bytes, actual Git roots/trees/product parity, Docker images/toolchain digests/extracted JAR and migration-set parity. Expose only a checked loader needed internally, never a generic policy bypass.
- Narrow `deploy/stable/backend-release.mjs`: retain operation body in lexical private `executeCore`; public checked `executeRequest(v1)` and checked `executeCandidateRequest(v2)` construct their own contexts. Normalize pair/compatibility/typed binding internally; preserve v1 output. Verify typed binding in fence/backup/rollback/postflight/release, original manifest hash/current artifacts pre-switch and actual target/original rollback artifacts after switch. Revalidate candidate bytes on repeated common observations.
- New `deploy/stable/candidate-release.mjs`: re-export checked candidate wrapper and strict separate Node CLI; existing v1 CLI stays v1-only. Reuse private paths/fresh results/exit conventions without exporting core.
- `helper-contract.mjs`: only a small shared path-rule seam if necessary; do not weaken v1 or add public flags.
- New `deploy/stable/tests/candidate-release.test.mjs`; extend existing `tests/support/runtime-fixture.mjs` only for bounded artifact/CLI transport needs. Real temporary Git/private files/tool bytes and genuine fixture-generated build result; Docker/SQL effects simulated at existing run seam.
- New `docs/releases/owned-candidate-v2.md`: request/receipt/phase contract and explicit source-only proof limits.

## RED/GREEN and checks

1. Verify new ignored worktree/base/toolchain; commit this plan before implementation. Node is existing absolute runtime binary, v24.22.1. Keep old161 worktree untouched.
2. RED `candidate_cli_generates_bound_release` and `candidate_rollback_uses_real_previous_frame`: real Node CLI/common seven operations, generated backup/postflight/release, actual previous frame. Preserve causal missing-behavior output.
3. RED table negatives: mixed v1/v2/unknown keys, published pairs, duplicate paths/pairs, transaction/instance/original manifest, source/tree/stage/tool/lock/build-result/image/JAR/migration substitutions, stale/cross-kind receipts, mutation between phases. Assert no lifecycle/SQL/backup effects after invalid proof; necessary independent reads are allowed.
4. GREEN sole validator/verified loader/private common core and CLI. Running A is observed as A before maintenance; prebuilt B proof uses stage/image/extraction, not B runtime or start. Identical V9 source migration set must match current actual fingerprint; common B observer only after switch, A after rollback. Existing quiet/active/client/capacity/data/rollback semantics remain.
5. Focused command: `node --test --test-isolation=none deploy/stable/tests/candidate-release.test.mjs`; regression: `node --test --test-isolation=none deploy/stable/tests/{candidate-release,backend-release,lifecycle,maintenance,backup,cli-integration}.test.mjs`. Run changed-source applicable local workflow tests; do not repeat unrelated JVM/OBS without a changed seam/workflow need. Retain exact head/tree/commands/toolchain/log hashes.
6. Measure added+deleted against intended base including this plan, docs and tests. Budget estimate 950 (validator/artifacts300, core/CLI180, tests420, docs/plan50); target850–1000. Report a necessary indivisible excess with exact responsibility/diff instead of dropping checks or moving the whole core.
7. Ordinary push/working PR to candidate/stable-v0.1.0, Refs only. Callback first PR/base/head/tree/diff/checks/limits to DevOps; lead creates ONE dedicated reviewer then same reviewer fixes. No author self-merge/stable publication. Producer merge does not wait for Web129 or release QA; after checked merge/ownEND archive worker/reviewer, preserve evidence.

No Docker/SQL/runtime/host/port/resource lease, auth/175/162 or protected human stack actions are authorized. Tests prove source/CLI composition, not actual PG or runtime preservation. Actual later A→B/B→B and publication require their separate owners/phases.
