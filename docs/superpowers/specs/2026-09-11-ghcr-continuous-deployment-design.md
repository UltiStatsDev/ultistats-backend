# GHCR Continuous Deployment Design

## Goal

Automatically deploy every successfully merged `master` commit to the UltiStats production VM. The VM must run a prebuilt immutable image from GitHub Container Registry instead of compiling source code locally.

## Scope

This change covers the application image and its delivery to the existing VM at `158.160.219.91`. PostgreSQL and Caddy remain managed by Docker Compose on the VM. Domain configuration, DNS, HTTPS, multi-instance zero-downtime deployment, infrastructure-as-code, and off-host database backups are outside this iteration.

## Chosen Architecture

GitHub Actions runs after a push to `master`. It executes the Gradle build, builds a Linux AMD64 Docker image, and publishes two GHCR tags:

- `ghcr.io/ultistatsdev/ultistats-backend:<commit-sha>` is the immutable deployment artifact.
- `ghcr.io/ultistatsdev/ultistats-backend:latest` is a convenience pointer and is never the authoritative production version.

After publishing, the workflow connects to the VM through a dedicated SSH key. The VM is pre-authenticated to GHCR with a read-only package token. GitHub Actions never sends registry credentials over SSH and the VM does not receive repository source code.

The server Compose file references `${APP_IMAGE}` for the application while PostgreSQL and Caddy keep their existing images, networks, ports, and persistent volume. Only Caddy exposes port 80; application port 8080 and PostgreSQL port 5432 remain internal.

## Alternatives Considered

Building from source on the VM was rejected because it requires Gradle, source checkout credentials, more CPU/RAM, and produces a less reproducible artifact. Copying a JAR over SCP was rejected because it bypasses the existing container packaging and makes rollback/version tracking less clear. A self-hosted GitHub runner on production was rejected because arbitrary workflow code would execute next to the production database.

## Deployment Flow

1. A PR passes the existing branch-name and Gradle checks and is merged into `master`.
2. The CD workflow checks out the merge commit and runs `./gradlew build` on Java 21.
3. Buildx builds `linux/amd64` and pushes the SHA and `latest` tags to GHCR using `GITHUB_TOKEN` with `packages: write`.
4. The deploy job connects to the VM as `appuser` using a dedicated key stored in GitHub Environment secrets.
5. Before changing the application, the VM creates a timestamped PostgreSQL custom-format dump under `/opt/ultistats/backups/` and verifies that it is non-empty.
6. The deploy script records the currently running application image, pulls the SHA-tagged image, and recreates only the application container.
7. It polls `http://127.0.0.1/v3/api-docs` through Caddy until it succeeds or the timeout expires.
8. On success, it records the deployed SHA/image in `/opt/ultistats/.env` and `DEPLOYED_REVISION`.
9. On failure, it recreates the application using the previously running image, verifies the rollback endpoint, and exits non-zero so GitHub marks deployment failed.

## Database Migrations

Flyway continues to run during application startup. Existing migration files are immutable: applied migrations must never be edited; schema changes require a new versioned migration.

The pre-deploy dump protects data recovery, but an application-image rollback cannot automatically undo a successfully applied forward migration. New migrations must therefore remain backward-compatible with the previous application version. A destructive or incompatible migration requires a separately reviewed rollout plan and must not rely on the generic automatic rollback.

## Secrets and Permissions

The repository workflow receives only:

- `PROD_SSH_PRIVATE_KEY`: dedicated deploy private key;
- `PROD_SSH_KNOWN_HOSTS`: pinned host key entry;
- `PROD_HOST`: `158.160.219.91`;
- `PROD_USER`: `appuser`.

These values live in a GitHub Environment named `production`. The workflow has `contents: read` and `packages: write`; the deploy job receives no broader repository permissions. The corresponding public SSH key is installed for `appuser`. The VM stores a separate GHCR credential with read-only package scope.

Secrets must never be printed, committed, embedded in the image, or placed in Compose command output. The existing PostgreSQL password remains only in the server-side `.env` with mode `0600`.

## Concurrency and Failure Handling

The workflow uses a single `production` concurrency group with `cancel-in-progress: false`, preventing overlapping migrations or deployments. Build or test failure prevents image publication and deployment. Backup failure, image pull failure, startup failure, or health-check timeout fails the deploy job. Application startup failure triggers an image rollback; PostgreSQL is never recreated by the deploy command.

## Verification

Repository verification covers:

- existing Gradle tests;
- `docker buildx build --platform linux/amd64`;
- Compose configuration validation;
- shell syntax/static validation for the deploy script;
- assertions that PostgreSQL has no published port and the application uses `${APP_IMAGE}` rather than `build:`.

Production verification covers:

- application, PostgreSQL, and Caddy container state;
- Flyway history containing only successful migrations;
- public `/v3/api-docs` response;
- `DEPLOYED_REVISION` matching the deployed merge commit;
- fresh application logs containing no startup error.

## Manual Rollback

If automatic rollback cannot complete, an operator connects by SSH and runs the server deploy script with a previously known SHA-tagged image. If a database restore is necessary, the application is stopped first and the selected custom-format dump is restored explicitly; database restoration is never automatic because it is destructive.
