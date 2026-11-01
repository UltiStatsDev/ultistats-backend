# Backend247 source bootstrap

Initial product baseline is `24704e11143a0bf919559a41324603f936ae03cf`; application candidates include reviewed tooling commits and use their actual full SHA. They are not published stable. No auth/V10 or currentmaster source is imported.

## Build interface

Prerequisites: existing Git, Node and Docker. No host Java, Python, jq or psql. Web stages a validated exact paired candidate before downtime, then runs the reviewed candidate's helper:

```text
node /absolute/backend/deploy/stable/build.mjs --request /private/request.json --result /private/result.json
```

Request file must be regular, caller-owned and private0600/0400. Result directory must be caller-owned0700. Request and result paths differ; result is new, never overwritten. SchemaVersion1 request fields: `sourceDir` absolute clean Git checkout, `expectedSourceSha` full40hex, `stageDir` new absolute path outside source, `imageTag`, `ownership:{instanceId,mode,project}`. Instance is12lowercasehex, mode stable/dev, project `ultistats-${mode}-${instanceId}`, imageTag `${project}-backend:${expectedSourceSha}`. Existing image tags refuse. Do not use a human/shared namespace.

Success exit0 returns schemaVersion1 plus `sourceSha`, `imageTag`, immutable `imageId`, extracted `jarSha256`, `productTree`, original `DockerfileSha256`, `toolchain`, `stageDir`, `generatedDockerfileSha256`, `baselineSha`, `productTrees`. Failure exit1 returns a private blocked result when its destination was validated; stderr contains a sanitized machine reason only. Missing result is failure. Caller checks both exit and result identity. Failed partial stages are retained for diagnosis and never consumed as success.

Source validation rejects wrong/abbreviated SHA, staged changes, untracked/ignored input, raw working-file byte/executable-bit mismatches and changes to baseline product/build boundaries. Index-only Git comparisons and raw blob checks avoid working-tree filters and index refresh. Every NEW source input is strict, including gradlew.bat; the separate legacy readonly EOL exception is never a build bypass. Snapshot reads immutable raw Git blobs with replacement interpretation disabled, verifies each blob hash and preserves executable bits; original index/checkout is untouched. Local checkout filters cannot change staged bytes. Unsupported symlinks/submodules refuse. Docker builds only the staged snapshot. Prerequisite images are explicitly obtained during staging when absent, then generated Dockerfile pins observed immutable repository digests. Recorded input IDs/digests are separate from source identity. Generated build-only Dockerfile adds --max-workers=2 to baseline Gradle commands; tracked Dockerfile is unchanged. Gradle wrapper and dependency downloads still require network on a first build. A local build can fail without disturbing the installed application.

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

Process/file boundary tests:17 passed on Linux using preinstalled Node; full Node suite58/58 passed. Docker boundary unit tests use a controlled process double; the separate actual build is recorded below. Synthetic adoption/backup/restore/isolation rehearsal and Mac runtime execution remain NOT_RUN. Shared QA S01–S24 remains separate. No human/auth runtime was used. No networks or volumes were allocated.

Actual Linux source build at `9e48101f4982b3b57a0fc1a73bdb4752c40f80f3` succeeded with Docker29.9.2. [Sanitized build evidence](evidence/bootstrap162-build-9e48101.json) records the actual image/JAR/input digests and source trees. Image labels matched the exact instance/source; readonly inventory after completion showed zero owned containers. The stopped artifact container was removed; source snapshot, image, JAR and private build result remain retained. No ports, volumes, networks, API or PG were started. This evidence proves that exact source; subsequent tooling/doc or integration SHAs remain distinct and are not relabelled9e48101. Actual baseline source wrapper is Gradle8.14.3; builder image8.14-jdk21 is a separate input identity.

A separate [actual247 build](evidence/bootstrap162-build-old247.json) supplies the previous unversioned fixture: source and image label are exactly247, with the trusted producer at6f999f1 outside that old checkout. This is distinct from tooling candidates9e/f556 and future integrated source. Its image is `sha256:b6b7e2de7b3e7e1879e8890d2d9ce307b6e84dc63e8eaf15239b22f548275e4a`; JAR is `25cc0a38f5e3f49e7b02a21b7f7cf05afe86f686ee2a6187ad2275441fef9f8e`. Reused Docker builder cache produced the same JAR bytes as f556, while source labels and image IDs remain separate. No old baseline release/version was invented.

The coherent private [helper+build engineering target](evidence/bootstrap162-build-integrated-ad7553b.json) is source `ad7553bcb67eff4db6df81d804d8ae04f0e0b6db`, image `sha256:9db201c403f0d4ddefc4b556e31cfc0fa13e8f62b9cafc83d6dc2d75f0a4957c`, with the same actual JAR bytes as old247. [Stopped-container metadata proof](evidence/bootstrap162-runtime-metadata-ad7553b.json) confirms Docker's container Image matches that immutable ID, entrypoint is `java -jar app.jar`, and no process or mount was started. Toolchain images are Linux/amd64 and declare JDK21.0.13+9. Builder's installed Gradle is8.14.6; source invokes the separate unchanged Gradle8.14.3 wrapper. These distinct versions are not conflated. This remains engineering artifact evidence, not a running installation or finalized release.
