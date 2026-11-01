# Auth153 engineering verification packet v1

Backend#153 under Backend#150; worker checkpoint, not merged release readiness or combined QA/PO acceptance. Frozen wire1.0.0: c48eef30bff9e5e89e34da545b0aaad643e3c993, SHA256 d9f83750f91758d95803ee8b06c85072c936eaa95bd4fe5867799e9ce873d5bf.

## Source and artifact

- Original sports baseline5c9781f2895141ca7a430588873c764d8c1e2dd6.
- Owned checkpoints: dc14fe9 policy/staff tools/CORS;7b7d95c actual mapping coverage/HTTP harness;fec4c73 role/capture/account and browser component;f6ff091 CORS ProblemDetail.
- Fully built checkpoint96e18dc3e444f1d94ee08123debb17e3bf77ed8e, treee7e007188bbb2baf0df116c7f38b60be5255b107. Includes core original-transaction fence, scoped legacy fixture3629395, timeout restoratione226b4a and nullable-context fixfc745fc.
- JAR SHA2561811782868b9019fecd80b555a50f7517c87a03d06f33ca085c7c0ea92e79b20; immutable /tmp/ultistats-auth153/artifacts/96e18dc.jar.
- Compared origin/master19d018155ffd74386a7855c3ae6bdb07a03df227. No worker merge; final atomic integration/review/CI belongs existing leads.
- Direct JAR library check: Framework modules6.2.19, Security modules6.5.11, BouncyCastle1.86, Boot3.5.7; no mixed Framework patch found.

## Executed checks

| Check | Result and limits | Evidence |
| --- | --- | --- |
| Inventory |71actual application mappings/24static;58enforcement plus13core/ingress explicitly owned; actual Spring resolution agrees | auth-surfaces-v1.json, inventory.py --check, AuthMappingInventoryTest |
| Focused unit/chain |135policy plus CORS/role/capture/account/CORS-body regressions;141JVM at CORS checkpoint | /tmp/ultistats-auth153-cors-final.log |
| Complete build |717tests,0failures,0skips; no production bypass | /tmp/ultistats-auth153-full-build-coherent.log and XML |
| Actual PG in build |EventBatch33, AuthConcurrency10, FenceTimeout2, AuthMigration1, PrecisionMigration1, OwnerCLI1 all executed | Same full build, own PG22153 isolated schemas |
| Node |15adapter and40existingOBS cases PASS; missing adapter/interceptor RED observed | /tmp/ultistats-auth153-js-regression.log |
| Chromium component |Production modules/Swagger5.21 with synthetic HTTP; owner switch stops save and preserves draft | /tmp/ultistats-auth153/browser-component.log; not backend proof |
| Real owner CLI |Nonweb bootstrap on owned PG succeeds after core fix; private stdin, clean exit, no listener | /tmp/ultistats-auth153/owner-cli-fixed.log |
| Actual HTTP/PG |9checks PASS on96e18dc, including all34protected unsafe routes | /tmp/ultistats-auth153/http-matrix-96e18dc.json |
| Actual Chromium/API/PG |OBS global/profile PUT, same-origin private preview, public scoreboard update, real Swagger POST201, public source no auth imports/requests/cookies; presentation restored/logout;0pageerrors | /tmp/ultistats-auth153/browser-real-96e18dc-third.log |
| Restart |Installation ID retained across actual owned API restart | /tmp/ultistats-auth153/installation-before-final-restart.json and current metadata |

Raw HTTP denies guest and each wrong installation/account/session UUID with malformed body and missing CSRF cookie, zero stale Set-Cookie and unchanged domain fingerprints. It checks temp downscope, organizer/admin separation, docs aliases/editor/previews, public live versus FINISHED-only JSON/HEAD/ZIP statistics, prior committed batch and immutable undo receipt after a later auth denial, and private portable JSON even for finished matches. A later rejection describes only that attempt; no false conclusion about an earlier lost response.

Public listener/TLS/verified proxy source proof belongs154. Lock races, same-writer termination and commit failure run through core real-PG tests. Chromium here uses explicit LOOPBACK, not native OBS or LAN certificate acceptance. General QA24 remains independent.

## Preserved failure history

- Actual nonweb CLI initially failed from scanned web controller needing a web-only bean; core d3174fd conditions/full-context regression fixed it.
- Actual ingress found plaintext CORS403; production-chain RED reproduced missing problem MIME. f6ff091 preserves checks and renders RFC9457/no cookies; valid preflight200 and anonymous private-path transport priority remain.
- Old legacy fixture had two any-request chains rejected by Security6.5.11; core3629395 scopes explicit legacy contexts via test header. Complete build then passed.
- HTTP harness incorrectly expected finish after undo left an empty log. Existing completed-point409 was correct; only the synthetic fixture changed.
- Browser harness awaited a transient saved label replaced by legitimate SSE synchronization. Oracle now awaits actual PUT/canonical readback; production unchanged.

## Outstanding product integration

1. Native HTML initial login/return and temporary-password preservation must use exact Web mapping through optional guarded core hook0881aa0. API/static errors stay ProblemDetail and public transport stays403.
2. Genuine standalone interaction must use the complete shared Web credential/activity coordinator. No passive renew, guessed partial IPC or secret storage.
3. Final atomic source/review/CI/checked merge/canonical target callbacks, native/shared QA and PO. Product issues remain open.

Rejected authority designs: outer HTTP transaction (breaks existing rollback/commit/response order) and independent fence connection (death TOCTOU). Current core fences the same existing transaction. Existing photo facade transactions guard filesystem entry; narrow missing service annotations are core-owned.

## Resources

Only own API21153/PG22153/uploads/synthetic identities. Exact host PID/start ticks/JAR/container/network/volume inventory and cleanup receipt: /tmp/ultistats-auth153. No human/default247/shared19584/foreign DB/uploads/queue/trust/DNS/CD changes. No external diagnostic-target lease issued. After own END remove only owned processes/container/network; retain volume/source/JAR/evidence.


## Shared Web coordinator consumer integration

This source replaces the historical independent staff adapter above with a thin
reexport from the frozen same-origin `/staff-auth-v1.mjs` Web route. Web owns
credential locking, IndexedDB expectations/logout intent/renewal, invalidation,
activity and masked-CSRF lifetime. Backend adds no auth storage or second actor.
OBS binds the initial page ticket, disables stale controls through subscriptions,
checks before mutations and after private body reads, and preserves drafts.
Swagger UI 5.21 wraps its existing `fn.fetch` transport with an actual `userFetch`
network callback through the shared protected fetch. Swagger retains its query,
JSON and multipart serializers; CSRF never enters its Redux request metadata.
Auth/account operations have neither Try it out nor network execution here.

Consumer component artifact: Web source
`59588dab2e2f836db04d70da6949b0a2a9f15ddc`, 55,655 bytes, SHA256
`e34333b8794ab124626a5a331d3765a4fc756bbad4ca957de0f5fdd61fc09d7a`.
The fixture checks the manifest against the bytes it actually serves and reports
that provenance. Canonical Web-root build uses Node24.22.1/Vite8.3.5 and pnpm lock
SHA256 `d4db84914d8f8bc2f9f3bc490d214308241849f69489f651eeae74e7505ae808`.
An earlier build from Backend cwd differs only in Vite source-path comments;
both artifacts and provenance remain under `/tmp/ultistats-auth153`.
This consumer artifact is not a final release freeze.

Focused JVM HTML/navigation (5), auth matrix (2) and actual mapping inventory (1)
PASS with zero skips; log `/tmp/ultistats-auth153/shared-helper-jvm.log`.

Current verification supersedes the historical 15 independent adapter tests:
54 Node cases PASS, including four shared transport boundary tests. Actual pinned
Swagger/Chromium plus exact Web bundle passes ten synthetic HTTP component checks:
OBS global/profile captured writes; changed actor blocks save/preserves draft;
Swagger POST and multipart file upload with network-only CSRF/secret-free Redux;
auth/accounts Try it out blocked; real BroadcastChannel invalidation disables old
Swagger controls/preserves input; real IndexedDB pending logout prevents cold
Swagger bootstrap; trusted renewal coalesces across tabs while passive bootstrap
and SSE do not renew; public links/previews and fresh public sources remain valid.
Logs: `/tmp/ultistats-auth153/js-shared-helper.log` and
`/tmp/ultistats-auth153/browser-component-shared-helper.log`.

This is component proof with synthetic HTTP responses, not new real PG/backend,
TLS, native OBS or canonical gateway deployment evidence. No leased live target
was changed. Combined source, final release artifact, CI/review and canonical
runtime/shared QA remain integration gates.


### Unconfirmed profile completion after session invalidation

Independent review reproduced a transient false outcome: server profile commit,
withheld successful response, session change/BroadcastChannel invalidation, then
response release caused the catch to announce “Не сохранено”. That message did
not describe server state. The catch now reports an unconfirmed result, retains
the draft and asks the operator to check the current profile before repeating.
Adjacent session/draft messages describe only data remaining on this page.
Capture fencing, disabled stale controls and no automatic replay are unchanged.

The component regression records every status text assignment, witnesses the
server's committed profile before invalidation, then releases the response. Its
old-message assertion failed RED before the fix. Final run passes all11 browser
checks with exactly one profile mutation for this scenario, retained draft and
disabled controls;54Node tests also pass. Logs:
`/tmp/ultistats-auth153/browser-component-uncertain-red.log`,
`/tmp/ultistats-auth153/browser-component-uncertain-green.log`, and
`/tmp/ultistats-auth153/js-uncertain-green.log`.
A first GREEN attempt instead timed out because an older pre-dispatch test still
waited for the removed wording; that test oracle was corrected before the final
successful run. This is synthetic component proof, not new backend/PG proof.
