# Backend247 source bootstrap

Initial product baseline is `24704e11143a0bf919559a41324603f936ae03cf`; application candidates include reviewed tooling commits and use their actual full SHA. They are not published stable. No auth/V10 or currentmaster source is imported.

## Build interface

Prerequisites: existing Git, Node and Docker. No host Java, Python, jq or psql. Web stages a validated exact paired candidate before downtime, then runs the reviewed candidate's helper:

```text
node /absolute/backend/deploy/stable/build.mjs --request /private/request.json --result /private/result.json
```

Request file must be regular, caller-owned and private0600/0400. Result directory must be caller-owned0700. Request and result paths differ; result is new, never overwritten. SchemaVersion1 request fields: `sourceDir` absolute clean Git checkout, `expectedSourceSha` full40hex, `stageDir` new absolute path outside source, `imageTag`, `ownership:{instanceId,mode,project}`. Instance is12lowercasehex, mode stable/dev, project `ultistats-${mode}-${instanceId}`, imageTag `${project}-backend:${expectedSourceSha}`. Existing image tags refuse. Do not use a human/shared namespace.

Success exit0 returns schemaVersion1 plus `sourceSha`, `imageTag`, immutable `imageId`, extracted `jarSha256`, `productTree`, original `DockerfileSha256`, `toolchain`, `stageDir`, `generatedDockerfileSha256`, `baselineSha`, `productTrees`. Failure exit1 returns a private blocked result when its destination was validated; stderr contains a sanitized machine reason only. Missing result is failure. Caller checks both exit and result identity. Failed partial stages are retained for diagnosis and never consumed as success.

Source validation rejects wrong/abbreviated SHA, dirty tracked/untracked/ignored input and changes to baseline product/build boundaries. Snapshot uses a separate Git index; original index/checkout is untouched. Docker builds only the staged snapshot. Prerequisite images are explicitly obtained during staging when absent, then generated Dockerfile pins observed immutable repository digests. Recorded input IDs/digests are separate from source identity. Generated build-only Dockerfile adds --max-workers=2 to baseline Gradle commands; tracked Dockerfile is unchanged. Gradle wrapper and dependency downloads still require network on a first build. A local build can fail without disturbing the installed application.

Helper never starts/stops application/DB, migrates, restores or operates an installation. It creates one stopped, network-none artifact container from the resulting immutable image, copies `/app/app.jar` and removes only that exact container ID. Images and private stage evidence are retained. The caller serializes builds and resource access; local engineering uses heavy exclusive, then browser-network exclusive, then JVM lock. macOS callers use their platform orchestration lock; `flock` is not an installation prerequisite.

Web114 consumes immutable imageId and actual labels `io.ultistats.instance` and `org.opencontainers.image.revision`. Normal prepared startup verifies the cached image and uses no build/pull/fetch. Image tag alone is never runtime identity. Backend161 additionally verifies actual DB/volume/writer identities, JAR/migration evidence and bounded sporting HTTP; Swagger alone is insufficient.

## Baseline parity

Before tooling implementation these exact Git objects matched247:

| Boundary | Git object |
| --- | --- |
| src/main | 5c81f8910e958d1b28f1a096b59fcb700875b9f8 |
| src/test | 404a8fa3faa99b1eb43627329dc76e3cae18529a |
| Dockerfile | bd01eaf4cda96923e4ba084ca34c208faeaba5b9 |
| build.gradle.kts | 846e10d293b236db9241b3a07358799f4da9b9da |
| settings.gradle.kts | 2e1a5d4e771e7111139113699fc5b0644c6b5923 |
| gradle | 4ce72237e22e29d0267b5cbe52a3a823c2be45ef |
| gradlew | 23d15a9367071145e9c79bb4ddf879d1fbe78b5d |

This includes static assets and migrations ending at V9. The helper rechecks these boundaries on every build. Historical sporting QA is not claimed from tree equality alone: accepted evidence must identify the original tested source and its provenance. Changed tooling boundaries receive new tests and actual engineering checks.

## Adoption and recovery boundary

Fresh/adopted installations use Web114's installation manifest, with Backend161 helper bridge. Existing real volumes/uploads/origin must be identified and retained; unknown ownership blocks. An empty DB is never a substitute for adoption. Stable/dev require distinct projects/volumes/ports. PG remains running on an isolated internal-only network while every exact owned app writer is stopped for backup; no DB host port. Target postflight requires maintenance-gated ingress, not a direct writable published API. Initial supported compatibility is explicitly proven247/V9 product source plus reviewed tooling, never arbitrary V9/V10/auth downgrade. Database restore is not an updater operation.

## Evidence status

Process/file boundary tests:16 passed on Linux using preinstalled Node; Docker was a controlled process double. Actual Docker source build, synthetic adoption/backup/restore/isolation rehearsal and Mac runtime execution are not yet claimed. Shared QA S01–S24 remains separate. No human/auth runtime was used. No containers/networks/volumes have been allocated by this slice so far.
