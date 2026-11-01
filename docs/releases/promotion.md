# Stable source promotion

Refs: Backend160 / parent159, Web112. This tooling does not publish or deploy a release. Initial product baselines are Web `39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba` and Backend `24704e11143a0bf919559a41324603f936ae03cf`; reviewed tooling produces different final application SHAs. Candidate branches are not stable releases.

## Contract and readonly prechecks

The authoritative record schema/validator is Web `releases/schema/paired-release-v1.schema.json` / `scripts/release/record.mjs`, checkpoint `b5c2770d7afa70533b9cae3c35aa3be9376cdb39`, schema SHA256 `2cb6c399b800b0bd1b3ff24e3e2b5791e4862575fa0f9a33cb6c3389213546e4`. Web owns full structural validation, metadata finalization and highest-valid-semver discovery. Backend does not implement a second finalizer or selector.

```sh
node deploy/stable/promotion.mjs verify --record /path/record.json --web-repo /path/web --backend-repo /path/backend
```

The CLI reads local Git objects only. It never fetches, checks out, creates/moves/deletes a ref, executes code from the provided checkouts, touches a runtime or dispatches CD. It emits JSON `{ok:true,version,backend:{commit,tag},compatibility}` with exit0; rejection emits `{ok:false,reason}` with exit1. Invalid syntax/options/duplicate flags fail closed. Git errors and unreadable/invalid JSON do not echo file contents or command stderr. Importable synchronous functions:

- `verifySource(repository, sourceSha)` returns the full commit SHA or throws. Refs, abbreviations and blob objects are rejected.
- `readExactTag(repository, tag, sourceSha)` requires stable `vMAJOR.MINOR.PATCH`, a lightweight tag whose raw object is the exact commit, and returns that SHA or throws.
- `planTag(repository, tag, sourceSha)` returns `{action:'create'|'noop',tag,commit}` without mutation; any existing conflicting/annotated tag throws. This is an advisory precheck, not a lock or permission to publish.
- `verifyBackend(record,{backendRepo})` returns `{commit,tag}` after checking its producer-owned source/tag/pin/compatibility subset.
- `verifyPairSources(record,{webRepo,backendRepo})` adds exact Web tag and `dev/backend/revision` read from the Web application commit; returns `{version,backend,compatibility}`.

Producer success alone does not mean a record is published, structurally valid or accepted by QA/PO. Call Web's canonical `validateRecord` and metadata-aware `discoverLatest` for that decision. Do not install a descriptor merely because this source precheck succeeds. Missing metadata, drafts, one-sided refs, changed refs, conflicting records and interrupted publication remain invisible to discovery. Advancing main/master or the metadata tip does not change the application pair.

V1 only advertises the explicitly accepted previous Web39/Backend247 pair and schema9-to9 application compatibility. Unknown V9 sources, V10/auth and future paths fail closed. `appRollback:true` is a capability declaration, never proof about an installation's live database, writers, backup or runtime; Backend helper operations must separately prove those facts. No automatic database restore exists here.

## Controlled publication after shared QA and PO

Only the authorized release operator performs these steps. Authors do not self-merge or publish refs/tags. Until shared QA and PO accept the exact application pair, prepare candidate source and evidence only.

1. Review source composition, exact CI and final application SHAs; verify Backend product source/migration/static parity to247 or explicitly reviewed boundary delta. Never merge/rebase master/auth into the initial247 candidate. Keep accepted baseline identities distinct from final tooling commits.
2. Ensure both local/remote repositories are current through an explicit operator-controlled fetch, compare existing remote raw refs and protections, and run readonly prechecks. Local mirrors alone cannot prove a remote ref has not changed. Require exact QA/PO evidence and actual Web pin. Protect `release` from force/deletion; protect `v*` against updates/deletion/reuse, with the controlled publisher as the creation actor. CI checking a tag cannot itself enforce immutability; verify repository rules separately.
3. Advance the eventual `release` lines only by reviewed controlled fast-forward/PR without force or rewriting published history. Create the same lightweight `v<version>` in each repo pointing to the accepted application commits. Publication is not cross-repository atomic: interruption after either tag is expected and must remain undiscoverable.
4. Read back both remote raw tag targets/types. Existing matching tags are an idempotent retry; a mismatch stops. Never repair a published version by moving a tag. Use a new version after correcting the defect.
5. Use Web's finalizer to add `releases/v<version>.json` in a subsequent metadata-only commit on Web `release`. The record references both application commits, exact pin, compatibility, prerequisites, evidence, notes/update/rollback. It has no own commit field. Tags continue to point at application code. An existing identical record is a retry; mutation/conflict refuses.
6. Read back metadata and both tags independently, run canonical full verification/discovery plus Backend precheck, and verify the selected highest valid finalized SemVer equals the accepted pair. A one-sided tag/draft/missing record is not a release. Publication never starts/stops/migrates an installation.

CI runs for PRs targeting master/release/candidate, pushes to the initial candidate/release/hotfix lines and version tags. The initial worker branch has an explicit push bootstrap trigger so CI exists before the first candidate-target PR. The unchanged CD workflow is master-only; stable/candidate/tag publication cannot trigger it. Repository-level CD disablement remains required; this work neither enables nor dispatches it.

## Hotfix and forward integration

Start a hotfix from the exact current stable application tag, not the Web metadata tip or moving master. Use an ordinary numbered `hotfix/<issue>-<description>` branch, preserve the compatible pair/pin and collect focused tests, exact-head CI, independent review, shared QA/PO evidence. Publish a new PATCH by the same paired procedure; do not include unfinished main/master product work.

Forward-port the fix separately to current master through an independently reviewed PR. Resolve integration differences explicitly and retain active auth CI/product code. Do not copy the whole247 CI file over modern master. Track the issue/PR/callback until forward integration is checked; before the next planned promotion verify the fix is still present and its regression test passes on that exact candidate. PRs use Refs and product issues stay open until shared QA/PO and actual publication.

## Local verification scope

Temporary-Git tests exercise exact/missing/changed/annotated refs, moving main, interrupted source preparation, retries, canonical sources, pins and initial compatibility, plus sanitized CLI and CI/CD isolation. Web tests own interrupted metadata publication, finalized record history/immutability and SemVer selection. No Docker/runtime is required for this slice. Linux Node verification does not establish executed macOS behavior.

All producer Git reads use command-scoped `--no-replace-objects` and remove inherited `GIT_*` directory/object/config overrides from the child environment. Local replace refs cannot alter the canonical application pin or commit type behind a fixed SHA/tag. Real-Git regressions cover both an accepted pair with misleading replacements and an invalid pin concealed by a replacement. These controls do not change global Git configuration.
