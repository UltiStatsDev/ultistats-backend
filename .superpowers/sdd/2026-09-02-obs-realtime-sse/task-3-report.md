# Task 3 report: Stream HTTP endpoint and OpenAPI contract

## Delivered

- Added MatchEventStreamController at the literal
  /api/v1/matches/{matchId}/events/stream route, avoiding the
  EventController /{eventId} route.
- The successful subscription response is text/event-stream with
  Cache-Control: no-cache; the missing-match response is an
  application/problem+json RESOURCE_NOT_FOUND problem whose instance is the
  request URI.
- Added OpenAPI declarations for the 200 text/event-stream string response
  and 404 application/problem+json ProblemDetail response.

## Recovered abandoned test

The worktree contained only the untracked candidate
src/test/kotlin/com/github/mihanizzm/ultistats/controller/MatchEventStreamControllerTest.kt;
there was no controller or report. Its three scenarios matched the required
endpoint, 404, and OpenAPI contract, so the file was retained.

The original SSE-body assertion was corrected. It expected a compact
data:{"matchId":"..."} line and the field order event, retry, data. That is
not the actual project behavior: JacksonConfig enables INDENT_OUTPUT, so
Spring writes the object over several data: lines, and SseEmitter writes the
frame as event, data, retry. The corrected test uses its bounded polling
helper, verifies those protocol fragments in their actual order, and parses
the joined data lines to assert the literal match ID. This checks the wire
payload without coupling the test to formatting whitespace.

## TDD and verification evidence

All commands used Java 21:
/Users/mixanizzm/Library/Java/JavaVirtualMachines/corretto-21.0.9/Contents/Home.

1. Initial recovered-test RED:

   ./gradlew test --tests "com.github.mihanizzm.ultistats.controller.MatchEventStreamControllerTest"

   Failed 3/3: existing-match and missing-match requests had no stream
   handler; /v3/api-docs had no stream path.

2. After identifying and correcting the flawed framing assertion, the
   controller was removed and the corrected test was rerun for RED with the
   same command. It again failed 3/3 for the missing handler/OpenAPI path.

3. GREEN:

   ./gradlew test --tests "com.github.mihanizzm.ultistats.controller.MatchEventStreamControllerTest"

   BUILD SUCCESSFUL.

4. Required controller regression:

   ./gradlew test --tests "com.github.mihanizzm.ultistats.controller.MatchEventStreamControllerTest" --tests "com.github.mihanizzm.ultistats.controller.EventControllerTest"

   BUILD SUCCESSFUL.

5. Full suite:

   ./gradlew test

   BUILD SUCCESSFUL.

The Gradle output includes pre-existing deprecation and dynamic Mockito-agent
warnings, but no test failures.
