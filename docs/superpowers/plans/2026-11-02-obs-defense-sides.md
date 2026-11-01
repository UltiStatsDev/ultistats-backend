# OBS defensive player sides — issue #167

Scope: presentation only. Canonical defensive events carry the attacking thrower in
`fromParticipantId` and the defending actor in `toParticipantId` (event request
contract and statistics aggregators agree). Show attacker left and defender right
for BLOCK, BLOCK_MARKER, BLOCK_FIELD, INTERCEPTION and CALLAHAN. Keep GOAL's
assister-left/scorer-right order. Preserve API, statistics, scoreboard and photos.

1. Confirm clean baseline and run focused feed/renderer tests.
2. Update regression expectations and add distinct team/number/name, zero number,
   UNKNOWN and missing-participant cases for every defensive type and GOAL control.
   Observe failures before changing production code.
3. Remove defensive reversal in `resolveRow`; run focused and complete JS tests.
4. Render production modules/CSS in Chromium with synthetic distinct teams and
   GOAL control. Check actual DOM order, labels, accent borders and screenshot.
   This proves browser rendering only; native OBS is a separate acceptance gate.
5. Run full backend build under bounded JVM lock; commit/push, open PR with
   Refs #167, check current CI and send exact head to independent reviewer.

No human/auth runtime replacement or deployment. Keep issue open for QA/PO.

## Verification receipt

- Focused regression: 8 expected failures before the mapping fix; 14/14 pass after.
- Complete JavaScript suite: 61/61 pass.
- Actual Chromium smoke: 8 rows, all defensive aliases plus GOAL, UNKNOWN and
  missing participant; text, physical left/right order and computed accent borders
  pass. Screenshot visually checked; evidence in `tools/obs167/evidence/`.
- Full JDK 21 `./gradlew build --no-daemon --max-workers=2`: successful.
  JUnit reports: 727 tests, 0 failures/errors, 47 skipped.
- Deployment safety script tests: pass (mocked deployment, no real rollout).
- Native OBS was not run. QA/PO acceptance and post-merge availability remain
  separate gates; this receipt does not claim deployment or close issue #167.

Reproduce the browser smoke with Playwright installed, optionally set
`PLAYWRIGHT_MODULE` and `CHROMIUM_PATH`, and run
`node tools/obs167/browser.mjs <evidence-directory>` under the shared browser lock.
