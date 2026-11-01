# Auth promotion Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. The existing permanent reviewer provides independent whole-branch review.

**Goal:** Replace automatic master delivery with explicit manual promotion of a full immutable release commit, before auth source merges.

**Architecture:** Keep the existing build/test/publish and production environment deploy jobs. Add a read-only resolver job that validates a lowercase 40-character commit SHA on master, passes that SHA to both checkouts and deployment, and rejects non-master workflow refs. Publish only the selected SHA tag; normal pushes never publish or deploy. Preserve production concurrency, verified SSH, backups, uploads preservation, health checks and rollback.

**Tech Stack:** GitHub Actions YAML, Bash, Git, existing deploy safety scripts and Gradle/Java 21.

**Spec:** Issue #151 (Refs #150); approved auth design `docs/superpowers/specs/2026-11-01-users-auth-design.md` at Web merge `50bd486f3a2001b50b3e3f63d2ae4bbcf6f4c3a8`; backend auth contract candidate c48eef3, Final permission and delivery scope. No product auth code in this branch.

## Global Constraints

- Existing assigned worktree only; baseline `5c9781f2895141ca7a430588873c764d8c1e2dd6`.
- CD stays disabled_manually; no enable, dispatch, SSH, secrets or runtime mutation.
- Full lowercase commit SHA only; no automatic master promotion or `latest` publication.
- Production environment and deployment safety checks remain mandatory. QA/PO release evidence is a procedural release gate, never a boolean proof.
- Commit/push approved; permanent reviewer and lead/root own review/merge. Refs #151 and #150 without auto-close.
- All JVM builds use flock `/tmp/ultistats-backend-gradle.lock`, JDK `/tmp/ultistats-backend-jdk21`, cache `/tmp/ultistats-backend-gradle-cache`, `--no-daemon --max-workers=2`.

## Review Focus

- Empty/short/branch/tag/injected input must fail before publication or SSH.
- A SHA outside master or a tree/tag object must fail commit selection.
- Dispatch from a non-master workflow ref must fail before credentials or publication.
- Build, deployment files, image and recorded revision must use the same resolved SHA.
- A normal push must not run promotion or publish a floating tag; environment approval is not QA acceptance.

### Task 1: Manual release selection and safety regression

**Files:** Modify `.github/workflows/cd.yml`, `deploy/tests/validate-workflow.sh`, `.github/workflows/ci.yml`, `README.md`; create `deploy/resolve-release.sh`, `deploy/tests/resolve-release_test.sh`.

**Interfaces:** `bash deploy/resolve-release.sh <release-sha>` consumes an already fetched Git checkout with `origin/master`, validates an exact commit reachable from master, and prints only that SHA on success; nonzero on invalid input. Resolver output `release_sha` selects all build/deploy source and image tag/revision.

- [x] Add policy assertions to existing workflow validation: only workflow_dispatch, required release_sha, no push/latest, same resolved SHA for both source checkouts and deploy. Run against baseline; expect failure identifying missing manual dispatch (RED).
- [x] Add executable release resolver tests using a temporary Git repository: accepted ancestor/tip; rejected empty/short/mixed-case/branch/injection/nonexistent/off-master/tree/tag SHA. Run before script exists; expect failure (RED).
- [x] Implement resolver and workflow: master-only resolver job with read-only permissions, full-history checkout, validated job output, selected release SHA checkouts, SHA-only image tag and deployment revision; keep all existing safeguards.
- [x] Wire resolver tests into CI and promotion safety checks. Update workflow validator for retained safeguards and new promotion policy. Run resolver and workflow checks; expect exit 0 (GREEN).
- [x] Update README with manual release runbook, exact-SHA selection, QA+PO/old queues/backup/rollback gates and current disabled/no-server status; no runtime execution.
- [x] Run `bash deploy/tests/deploy_test.sh`, `bash deploy/tests/validate-compose.sh`, `bash deploy/tests/validate-workflow.sh`, `bash deploy/tests/resolve-release_test.sh`, full locked Gradle build, existing Node tests and `git diff --check`. Expected: pass; record actual failures/limitations.
- [ ] Commit and push branch, open working PR Refs #151/#150, wait for actual exact-head CI, request existing independent reviewer. Re-read disabled workflow state and send exact PR/head/results to lead. No self-merge.

## Execution record

Plan self-review: requirements and five failure classes mapped to Task 1. Native execution already authorized; no additional plan/start permission. Resource inventory: no containers, runtime services or consumer leases planned. Shared Gradle build lock only.


### Local verification evidence

- Plan committed first: `7ae0782` before implementation.
- RED: workflow validator rejected original automatic workflow (`missing: workflow_dispatch:`); resolver test rejected valid fixture commit before resolver existed.
- GREEN: resolver accepted master ancestor/tip and rejected 11 unsafe selections; workflow policy, Compose config and deployment backup/uploads/rollback tests passed.
- Five temporary workflow mutations were each rejected: added push, latest publication, wrong execution SHA, removed production environment, removed master-only dispatch guard.
- YAML parsed using PyYAML BaseLoader; only workflow_dispatch event and required SHA input. Bash syntax and `git diff --check` passed.
- Full locked JDK21 Gradle build passed: 512 tests, 0 failures/errors, 27 skipped. Existing unused-variable, JVM CDS and Gradle deprecation warnings remain. Sandbox-only first build could not open Gradle network socket; authorized host-context rerun passed. Log `/tmp/ultistats-151-gradle-build.log` is local evidence only.
- Existing Node suite passed: 8 test entries, zero failures. Log `/tmp/ultistats-151-node-tests.log` local only.
- Ruling: retain existing SHA-tag deploy interface rather than changing deployment/rollback to registry digests; #151 requires explicit full release SHA and existing deploy.sh validates the matching image tag. Registry tag mutability remains a known infrastructure property, not evidence of a floating source selection.
- Cleanup receipt: temporary Git/policy fixtures removed via traps/temporary directories; no real Docker objects, API processes, ports, volumes, user data or consumer leases created. Build outputs/source/evidence retained; shared build lock released.
- No CD enable/dispatch, secrets, SSH or runtime operations performed. Initial read-only GitHub API: workflow355721783 disabled_manually.
