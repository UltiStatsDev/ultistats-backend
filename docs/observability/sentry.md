# Optional application reporting

The Jakarta starter and Logback module are pinned to Sentry Java **8.62.0**.
Ordinary SLF4J application logging remains available on the console. SDK error
capture and Logs are opt-in; no account, DSN, or outbound telemetry is needed
for local tests or normal operation without Sentry.

## Runtime contract

| Variable | Unset default | Meaning |
| --- | --- | --- |
| `SENTRY_DSN` | absent | Opt-in SDK destination. Missing or empty disables sending. |
| `SENTRY_ENVIRONMENT` | `local` | Environment on events and Logs. |
| `SENTRY_RELEASE` | empty | Supplied release string, accepted unchanged. |
| `SENTRY_SERVICE` | `ultistats-backend` | Service tag on events and attribute on Logs. |

The later manual configuration consumer must supply its actual application
revision, e.g. `ultistats-backend@<applicationSHA>`. The application does not
infer that revision from an image tag or add a build/Git plugin. This source
change does not wire compose, update a runtime, or activate a real DSN.

There is intentionally no `sentry.dsn` default in application.yaml. Spring's
environment binding supplies it when present. `sentry.logs.enabled=true` is
the 8.62 property (not `logs.enable`). Logback is an explicit dependency;
the starter alone does not include it. Its initializer attaches the appender
to `com.github.mihanizzm.ultistats` and the two default Spring background error
loggers, preserving existing console appenders.
ERROR logs create issues; WARN+ also becomes Logs. Unhandled MVC exceptions
are captured by the SDK resolver after normal exception advice. Framework
console errors outside those two background boundaries are not forwarded.

The narrow background loggers are `TaskUtils$LoggingErrorHandler` under
`org.springframework.scheduling.support` and `SimpleAsyncUncaughtExceptionHandler`
under `org.springframework.aop.interceptor`. Repeating scheduled failures keep
Spring's log-and-suppress behavior so later ticks continue. Void async failures
use Spring's default uncaught handler; caller-handled async Future failures
remain on their Future. No production async feature or executor is added.
Custom error handlers that consume failures must still log or capture them.

No tracing sample rate, profiling, source-context upload, SDK Gradle plugin,
or custom logging facade is introduced. Auto session tracking and client
reports are disabled. Expected security exceptions and async/client-abort
disconnect exceptions are ignored for issues. Existing stream delivery
handling still consumes benign disconnects; unexpected listener failures
and swallowed storage/cleanup failures log ERROR with a throwable.

## Outbound privacy boundary

`SentryPrivacyConfiguration` supplies both SDK-supported callbacks:
`SentryOptions.BeforeSendCallback` and
`SentryOptions.Logs.BeforeSendLogCallback`. One callback does not sanitize
the other telemetry type.

Events retain request method and a URL stripped of userinfo/query/fragment.
All request headers (including Authorization/Cookie/Set-Cookie), cookies,
body, environment/other data are discarded by rebuilding the request from
that allowlist. User/server identity, breadcrumbs, arbitrary contexts and
thread data are removed. Exception type/mechanism/stack remain; arbitrary
exception messages and frame variables are removed. Event log messages use
the original template without parameter values. Extras/tags accept only
canonical UUID values under matchId/eventId/playerId/teamId, plus the
configured service. SLF4J key-value UUID context is copied for listener issues.

Logs retain the original template when parameterized; formatted arguments,
SDK message parameters, arbitrary MDC/user/server/custom attributes are
removed. Only SDK origin/version, environment/release and canonical domain
UUID attributes survive; the configured service is attached explicitly.
Simple credential assignments and Bearer values are filtered in message
text. Keep credentials out of literal/free-form log messages: this small
SDK boundary is not a general detector for arbitrary secrets embedded in
unlabelled text. Console output is not rewritten by these callbacks.
Configuration values, route paths and static message templates must be
non-sensitive. Request bodies are deliberately unavailable even for
non-auth endpoints. This trades some diagnostic detail for privacy.

## Local proof

`SentryApplicationIntegrationTest` boots the real application with H2 and
the actual main Sentry configuration. Only its synthetic test endpoints and
in-memory SDK transport are added. It checks exactly one HTTP 500 issue
with a stack, ERROR/WARN behavior, actual handled upload/auth failures,
SSE disconnects, swallowed operational failures, and serialized privacy on
both event and structured-log paths. Captured synthetic envelopes are in
`build/sentry-proof`; no HTTP transport is created by those tests.
`SentryDisabledTest` covers missing/empty DSN against the main config, and
`SentryEmptyDsnStartupTest` checks the real auth ingress with an empty DSN.
`SentryBackgroundTest` loads the main config with the same in-memory transport,
executes audit cleanup on a bounded real scheduler, and invokes test-only
Spring async proxies. It checks continuation, exactly one issue per unhandled
failure, event/Logs privacy, Future behavior, unrelated-framework exclusion,
and missing/empty DSN. Envelopes are in `build/sentry-background-proof`.

This proves SDK/application serialization and filtering, not SaaS ingestion,
project configuration, UI issue grouping, alerting or production activation.
Parent #178 remains open for the separately owned manual wiring and acceptance.
