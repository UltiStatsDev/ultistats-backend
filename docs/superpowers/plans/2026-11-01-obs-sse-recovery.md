# OBS terminal SSE recovery — issue147

Approved scope: https://github.com/UltiStatsDev/ultistats-backend/issues/147, parent135. Base8ec755a31ddc5791b409831f95f023702480ded9. Inline execution/TDD; technical decisions delegated, no renewed product approval. Existing135 runtime/artifacts remain unchanged.

Audited source: shared-worker.js retains non-null EventSource on every error; startSource refuses any non-null source. Browser CONNECTING already has native retries, but terminal CLOSED after HTTP503 has none. Node RED confirms no recovery timer for CLOSED/initial failure. Actual browser must independently demonstrate readyState0→2 and no further streamGET against real backend/proxy before repair; scoreboard REST polling is not transport proof.

- [ ] Node RED: CLOSED vs CONNECTING, initial503 exponential delays1000/2000/4000/8000/15000 cap, reset only real connected, single shared connection, new subscribers do not bypass backoff, stale callbacks, unsubscribe/expiry cancels retry.
- [ ] Actual baseline browser RED using own API19856/PG19456/proxy19857: successfulresponses from backend unchanged, stream200/drop/503/restore and initial503 become CLOSED/no further GET; fresh acceptedGOAL absentEventLog despite scoreboardpoll.
- [ ] Minimal shared-worker.js fix: own bounded timer only for CLOSED (browser handles CONNECTING); detach failed source, identity guards all callbacks, subscriber-aware retry/cancel/reset. No widget polling/SSE, API/schema/DB changes.
- [ ] Node GREEN full suite, actual browser recovered liveSSE before acceptedGOAL/EventLogvisible, two matches/multipleSB+EventLog+admin/config/profile routing, boundedactiveconnections, cleanup, urgenthungGET≤2s with VERIFIED liveSSE; constructor/oldcallback boundaries if encountered.
- [ ] Fullbuild/JS/actualCI; newPR Refs147+135, independent permanentreview, no selfmerge. No unrelatedrawexport/imagebuild; singleimagebuilder144 throughlead.
- [ ] Immutable exactheadjar/hash/manifest/assetsdelta vs8ec+ea3; separate19856DB/uploads/fixtures/runcommands. NativeQA targetedretake via lead afteractualmerge/availability;147/135remainopenuntilQA+PO.

Risks: double retry loops for CONNECTING, delayed stale errors closing replacement, last subscriber expires while timer scheduled, constructors fail synchronously, infinite fast retry under repeated503. Existing leases/routing/config/profile behavior is binding. Browser engineering evidence is distinct from nativeOBS QA.
