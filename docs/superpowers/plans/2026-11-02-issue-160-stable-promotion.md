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
