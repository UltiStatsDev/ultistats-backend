# Historical backend cleanup

Approved by the user on 2026-10-31 after a dedicated grilling. This document records the approved requirements; it does not introduce another approval gate.

## Goal and boundary

Deliver the existing backend package #125 and #127–131 without duplicating PR #126. Prioritize #131 because Web issue #39 depends on it. Contract issues #128–130 and review of the existing OBS work can proceed independently. #71 already delivered its backend changes; frontend migration and field testing remain open and do not block this cleanup. Preserve v1 until its migration conditions are met.

Technical completion means the package is merged, backend checks pass, a reproducible integration target is available, and mandatory callbacks have reached the dependent team. User-facing or shared QA acceptance may remain explicitly pending in open issues. Report technical completion to the product owner before taking new product assignments. Add newly discovered work to this fixed package only when essential for its acceptance.

## Approved approach

Continue the existing Kotlin/Spring application, repository, tests, migrations and API. Reuse PR #126 instead of rebuilding OBS. Preserve clients and historical data with compatible additions; avoid API removals and unrelated architecture changes. Independent issue implementations use separate worktrees and pull requests with independent review. Deployment is not part of this work: CD is disabled and there is no remote server.

## Statistics

### Team breaks (#131)

A break is a goal by the team that pulled in the same point, including CALLAHAN. Expose a nonnegative integer `teams[].attack.breaks`. GOAL uses the receiver's team; CALLAHAN uses the defender's team. Turnovers alone are not breaks. Recalculate from canonical events, preserving UNKNOWN snapshot identities, undo, multiple points and halftime boundaries. Existing score, defense and time semantics remain unchanged.

### Average possession duration (#127)

The metric is the mean active duration of completed player holding episodes. PICKUP, receiving PASS and INTERCEPTION start an episode. Passing, scoring, losing the disc or another canonical transition ending ownership completes it. Timeout pauses duration without splitting the episode. Exclude an episode that remains open at the end of the available event log. A scoring catch or CALLAHAN does not start an additional zero-duration holding episode. No completed episodes means unavailable, not a measured zero. Completed episodes lasting 4, 6 and 2 seconds yield 4000 ms. Preserve the existing `discPossessions` meaning.

Technical compatibility decision: retain numeric `averagePossessionTimeMs` for old clients and add `averagePossessionTimeAvailable` to distinguish unavailable from a measured zero; use 0 only as the legacy placeholder when availability is false. Document that new consumers must inspect availability. The implementation must preserve existing non-average time totals and export compatibility.

## API contracts (#128–130)

Repair unproductive base-oneOf/leaf-allOf cycles while retaining discriminator mappings and variant fields for create/read/patch and v2 batches. Raw OpenAPI must generate compilable TypeScript without consumer normalization. Nullable and omittable properties must match runtime omission/null/value behavior, including ALWAYS responses. ProblemDetail and EventBatchProblem `instance` accept URI references. Preserve the actual empty 404 response of missing-match v2 GET and document it accurately rather than introducing an unannounced wire change.

## OBS (#125 / existing PR #126)

Review the existing transparent overlay and shared settings implementation against issue #125. Automated browser evidence does not substitute for a real OBS Browser Source test. The permanent QA role performs that test and an independent browser-session test using isolated OBS configuration, backend and synthetic data. If the real test cannot run, record the exact blocker and necessary manual step; keep the issue open until acceptance. Technical readiness still follows the cleanup milestone above.

## Verification and handoff

Each implementation supplies focused regression tests, `./gradlew build`, and successful CI for the reviewed commit. Use real PostgreSQL for migrations and database-specific behavior; H2 with Flyway disabled is insufficient for those checks. Verify runtime fixtures against raw schemas and compile generated TypeScript for contract fixes. Replay, undo and rebuild must agree for statistics. Recheck compatibility after integrating independently merged changes.

All test data and containers are isolated and synthetic. Do not mutate existing operator sessions, their queues, databases or uploads. Review and merge the exact checked commit, respect branch protection, return findings to the author, and provide integration instructions and actual check results. Do not auto-close issues awaiting QA. Cross-team issue bodies identify the mandatory callback, recipient and dependent issue; notify as soon as the change is merged, checked and available, without waiting for overall product QA.
