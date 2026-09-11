# GHCR Continuous Deployment Implementation Plan

> **Goal:** Build every accepted `master` revision once, publish it to private GHCR, and safely deploy that immutable image to the existing production VM.

## Task 1: Add an isolated production Compose definition

**Files:**
- Create: `deploy/compose.prod.yml`
- Create: `deploy/.env.example`
- Test: `deploy/tests/validate-compose.sh`

1. Write a failing validation script that requires the application to use `${APP_IMAGE}`, forbids an application `build:` section, and forbids a published PostgreSQL port.
2. Run the script and confirm it fails because the production Compose file does not exist.
3. Add the production Compose definition for `app`, `postgres`, and `caddy`, preserving an internal application/database network and the existing PostgreSQL volume.
4. Add a non-secret environment template documenting required server values.
5. Run Compose rendering and the validation script until both pass.

## Task 2: Implement a transactional server deploy script

**Files:**
- Create: `deploy/deploy.sh`
- Create: `deploy/tests/deploy_test.sh`

1. Write a hermetic shell test with fake `docker`, `curl`, and dump behavior for the success path, empty-backup failure, and failed-health-check rollback.
2. Run the test and confirm it fails because `deploy.sh` does not exist.
3. Implement strict input validation, locked single-process execution, custom-format database backup, non-empty backup verification, old-image capture, SHA-image pull/recreate, bounded health polling, atomic revision recording, and application-only rollback.
4. Run the shell tests and syntax checks until they pass.

## Task 3: Add the GitHub Actions CD workflow

**Files:**
- Create: `.github/workflows/cd.yml`
- Create: `deploy/tests/validate-workflow.sh`

1. Write static workflow assertions for `master` push only, least-privilege permissions, Java 21 build, Linux AMD64 Buildx publishing, immutable SHA deployment, production environment, concurrency, known-host verification, and no registry secret forwarding.
2. Run the assertions and confirm they fail because the workflow does not exist.
3. Add build-and-publish and deploy jobs using official GitHub/Docker actions and the production environment secrets from the approved design.
4. Run workflow validation assertions until they pass.

## Task 4: Document provisioning, deployment, and rollback

**Files:**
- Modify: `README.md`

1. Document the GHCR package, required GitHub Environment secrets, one-time VM registry login, dedicated SSH key installation, server file layout, automatic flow, verification URL, and manual rollback command.
2. Explicitly document that image rollback does not undo Flyway migrations and that forward migrations must be backward-compatible.
3. Check all documented names and paths against the actual workflow and scripts.

## Task 5: Verify and review the complete change

**Files:**
- Verify all files above

1. Run deploy shell tests and static validations.
2. Run `docker compose config` for the production definition with test environment values.
3. Run `docker buildx build --platform linux/amd64` without publishing.
4. Run the complete Gradle test suite on Java 21.
5. Run the repository self-review harness, address findings, and repeat affected verification.
6. Commit, push the feature branch, open a pull request linked to issue #98, and wait for required checks.

## Task 6: Provision production and prove the first CD deployment

**External state:** GitHub repository/environment, GHCR, and `appuser@158.160.219.91`

1. Install the dedicated public deploy key and the reviewed production Compose/deploy files on the VM without replacing the PostgreSQL volume.
2. Configure the private GHCR read-only login on the VM; if a token is not already available, stop and request that single credential from the user.
3. Configure GitHub `production` environment secrets without exposing their values.
4. Merge only after checks pass, then observe the `master` CD run.
5. Verify containers, Flyway history, public OpenAPI, preserved data, deployed revision, and clean startup logs.
