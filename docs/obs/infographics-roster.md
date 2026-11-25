# Protected infographic roster

`/obs-sources/infographics.html` accepts an EXACT_INFOGRAPHICS match binding or
an AUTO_INFOGRAPHICS binding. Issue/current/rotate/revoke use the existing staff
source-key API and the additional Infographics card in OBS administration.
Keep the generated fragment-bearing link private. The source uses only its
narrow GET endpoint; it never fetches public matches, general statistics or SSE.

The snapshot reads authorization, AUTO selection, lifecycle, match teams and
PLAYER participant snapshots in one original REPEATABLE_READ transaction.
UNKNOWN service actors and live global team membership are excluded. Photos are
optional; this first implementation consistently renders initials. Names and
jersey numbers come from the match snapshot.

Before the first active PULL, an in-progress match presents five players per
team every five seconds. The shorter team holds its last page. One full pass
ends at the persisted match start plus the larger team's page count times five
seconds. Reconnect and reload do not restart the pass. PLANNED, FINISHED, empty
AUTO and active PULL states hide the roster. Undo recomputes the phase from the
committed journal. Point lineups, leaders and postmatch displays are later leaves.
Three independent interval fields currently return read-only defaults of five.

`/obs-preview/infographics.html` is keyless and requires the captured OBS_ADMIN
staff session through the unchanged deployment-provided `staff-auth-v1.mjs`.
Session loss hides the panel and stops polling. Viewer bearer credentials grant
no staff access. The previous four scopes and derivation bytes remain unchanged.

Focused checks:

```sh
python tools/auth153/inventory.py --check
node src/test/js/obs-infographics.test.mjs
node src/test/js/obs-active-links.test.mjs
./gradlew test --tests '*ObsInfographicsContractTest' \
  --tests '*ObsSourceKeysIntegrationTest.infographics*' \
  --tests '*AuthMappingInventoryTest' --tests '*AuthSurfacePolicyTest' bootJar
```

`src/test/js/obs-infographics.browser.mjs` consumes an owned fixture descriptor
on stdin: origin, PostgreSQL container name, evidence directory, own public CA
path, Chromium launcher, installed Playwright module and frozen helper path.
It requires the real JAR, empty owned synthetic PostgreSQL installation and
normally validated HTTPS. Its launcher must use an isolated trust store; TLS
ignore flags are unnecessary. It writes sanitized results and screenshots,
never viewer URLs, cookies, tokens or traces. This technical seam does not
constitute ProductQA, native OBS acceptance, deployment or release approval.
