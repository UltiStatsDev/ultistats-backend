# Backend160 concrete code plan

Refs: https://github.com/UltiStatsDev/ultistats-backend/issues/160 (parent159; Web112 dependency).

Base: `24704e11143a0bf919559a41324603f936ae03cf`; branch `agent/feature/160-stable-promotion`. No merge/rebase from master. Product Kotlin/resources/static/migrations remain baseline-identical. The approved Web spec/coordination plan is commit `a23e7144bac89731996f5eb1a2ae2309bb28beca`.

## Ownership and dependencies

Owned files: `.github/workflows/ci.yml`, `deploy/stable/promotion.mjs`, `deploy/stable/promotion.test.mjs`, `deploy/stable/workflow.test.mjs`, `docs/releases/promotion.md`, this plan. Change `.github/workflows/cd.yml` and `deploy/tests/validate-workflow.sh` only if source guards require it; do not enable/dispatch CD. No backend-release/build implementation edits (161/162).

Web112 owns authoritative descriptor schema/validator and finalization/selection. Before dependent code, freeze its exact source path/hash and producer fields: protocolVersion, version, state, baselines, repositories.{web,backend}.{repository,sourceSha,tag}, backendPin, compatibility, requirements, notes, evidence (currently proposals, NOT a competing schema). Lifecycle already agreed: both lightweight immutable application tags, then metadata-only Web release commit at releases/v<semver>.json; record has no own SHA. Only finalized verified pair is discoverable; select highest valid SemVer. Missing/draft/one-sided/changed refs are rejected. Publication follows shared QA+PO and is never a worker action.

## Interfaces and implementation sequence

1. Commit this plan, send startup/ownership and dependency handshake to Backend lead and Web112; ask lead to freeze candidate CI target (`candidate/stable-v0.1.0` proposed). No PR against master/auth.
2. Independent RED Git-boundary tests use temporary owned local repositories only. `readExactTag(repository, tag, expectedSha)` must reject missing, annotated or moved tags; `verifySource(repository, sourceSha)` must require an existing full commit; moving main cannot affect exact identity. `planTag(repository, tag, sourceSha)` returns create/noop/conflict without mutation. Tests inspect refs before/after to prove readonly and idempotent retry.
3. After schema handshake, `verifyPair(record, {web, backend})` consumes the authoritative record validation, checks both exact tag targets and Web application `dev/backend/revision` equals Backend sourceSha. `verifyPublication(...)` additionally requires the exact finalized record on Web release metadata history. CLI uses explicit record/repository paths, prints sanitized JSON and nonzero for rejected verification; no network fetch, tag creation, runtime or CD side effects. Final function names/envelopes follow the frozen shared contract and are recorded here before implementation.
4. CI RED assertions require PR targets master/release/candidate, candidate/release/hotfix push checks and tag checks, Node promotion tests and existing JVM/deploy/OBS jobs. Branch validation uses head_ref for PR/ref_name for push and accepts only explicitly named stable branches/version tags or existing issue naming. Freeze lead target before wiring. Existing deployment workflow remains master-only and disabled at repository level; stable publication cannot trigger it.
5. GREEN minimal Git verifier then schema-dependent pair verification. Tests: partial tags; absent/draft/malformed record; tag mutation; main advancement; interrupted metadata publication; exact retry; mismatched pin; invalid full SHA/tag; metadata source mistaken for application commit; hotfix starts exact stable and retains fix in forward integration. Assertions prove installed sources/refs are never mutated.
6. Docs explain controlled operator publication order and exact readback, no published refs before QA/PO, immutable mismatch refusal, metadata retry, branch protections, hotfix from exact application tag plus forward-port to master with independent review preserving auth CI. No self-merge. PR uses Refs160/159.

## Validation and evidence

RED then GREEN: `node --test deploy/stable/promotion.test.mjs deploy/stable/workflow.test.mjs`; existing deployment shell checks and OBS Node suite. Baseline product parity: `git diff 24704e11143a0bf919559a41324603f936ae03cf -- src build.gradle.kts settings.gradle.kts gradle gradlew gradlew.bat Dockerfile`. Full build, if required by reviewer/CI, uses JDK21 and flock `/tmp/ultistats-backend-gradle.lock`, `--no-daemon --max-workers=2`; no Docker or runtime resources needed for this slice. Actual exact-head GitHub CI required after lead freezes candidate/bootstrap topology. Mac source portability is not a claim of executed Mac testing.

## Execution ledger

- Preflight: assigned branch clean at exact247. Baseline CI only pull_request/master; baseline CD only push/master. Shared interfaces are Web112 record ->160 verifier and lead candidate topology ->160 CI; both need exact freeze before dependent implementation.
- Existing permanent reviewer performs independent review; existing authorized merge operator integrates. No new reviewer agent or plan/start approval.

### Frozen record/producer interface (W112 + leads)

Authoritative Web `scripts/release/record.mjs`: sync `validateRecord(record)`, `verifyRecord(record,{webRepo,backendRepo})` return record or throw; `discoverLatest({webRepo,backendRepo,metadataRef='refs/remotes/origin/release'})` record|null. Backend does NOT duplicate these schema/finalization/discovery functions. Record fields: schemaVersion1,status finalized,version without v,repositories.web/backend{repository,commit,tag},backendPin,compatibility{fromSchema9,toSchema9,appRollbacktrue,supportedSourcePairs:[{web,backend}]},prerequisites{git,node,docker,compose},evidence{qa,po},notes/update/rollback text. Initial supportedSourcePairs is exactly Web39/Backend247, unknown V9 rejected.

Backend exports readonly `verifyBackend(record,{backendRepo})` -> `{commit,tag,compatibility}` or throw for its producer subset (not a full record validation claim), `verifyPairSources(record,{webRepo,backendRepo})` adds Web application lightweight tag and exact app pin. CLI `node deploy/stable/promotion.mjs verify --record <json> --web-repo <local-path> --backend-repo <local-path>` -> stdout `{ok:true,version,backend:{commit,tag,compatibility}}` exit0 or `{ok:false,reason}` exit1. No arbitrary JS execution from the provided repositories, fetch, ref mutations or publication. W112 full record validation and metadata visibility remain mandatory in its consumer.

- RED evidence: seven temporary-Git boundary tests failed ERR_MODULE_NOT_FOUND (implementation absent), using preinstalled Node with `--test-isolation=none` because default subprocess reporter suppresses failures in this sandbox. GREEN: seven passed after minimum readonly ref/source implementation. No runtime/Docker.
- Push ca9efbc blocked by automatic approval review (insufficient trusted export authorization for plan payload to existing repository); exact rejection reported privately to lead. No alternate transport/actor used.

- Frozen owner checkpoint: Web b5c2770d7afa70533b9cae3c35aa3be9376cdb39; releases/schema/paired-release-v1.schema.json SHA256 2cb6c399b800b0bd1b3ff24e3e2b5791e4862575fa0f9a33cb6c3389213546e4. CLI output compatibility is top-level, backend is exactly {commit,tag}; Web owns complete structural/metadata validation. Producer subset success is not publication acceptance.
- GREEN evidence: 17 Node tests pass (7 initial Git,5 pair,1 CLI,1 ambient Git override,3 workflow). Pair/CLI/CI/env regression each watched RED before GREEN. Existing OBS41 pass; deployment compose/deploy/workflow shell checks pass. Full Gradle build offline with JDK21, shared JVM lock, --no-daemon --max-workers=2: BUILD SUCCESSFUL in37s (sandbox interface failure required ordinary build escalation).
- Baseline parity: git diff247 -- src build.gradle.kts settings.gradle.kts gradle gradlew gradlew.bat Dockerfile is empty. No product source or migration/static/build dependency changes. No runtime or Docker resources created.
- CI delta: candidate/stable-v0.1.0 bootstrap plus release/hotfix/version-tag ordinary checks; original master PR/build/OBS/deployment checks retained. CD file unchanged and master-only. Forward-port master is a separate reviewed integration retaining auth CI; no currentmaster merge/rebase.
- Remaining gates: independent permanent review, accepted target/bootstrap branch readback, GitHub exact-head checks and push/PR availability (current push auto-review refusal retained privately), then lead integration. Shared QA/PO/stable publication remains later; product issues open.
- Integration correction from162: build boundaries live under deploy/stable/tests, so CI uses recursive NUL-safe test discovery rather than a root-only glob. New regression RED then GREEN;18 local tests pass. Helper/build authors retain file ownership.
