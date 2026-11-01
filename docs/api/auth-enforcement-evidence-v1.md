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
