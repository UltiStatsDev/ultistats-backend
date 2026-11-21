# Staff OBS preview

F1 provides a directly usable preview in an existing same-origin HTTPS session
with `OBS_ADMIN`. It neither issues nor accepts viewer keys. The iframe URLs are:

- `/obs-preview/scoreboard.html?matchId=UUID`
- `/obs-preview/event-log.html?matchId=UUID`
- `/obs-preview/scoreboard.html?mode=auto`
- `/obs-preview/event-log.html?mode=auto`

Only one query parameter is accepted; fragments and credentials in URLs are
rejected. The parent and frame must share the staff origin. Private pages,
`/obs/staff-preview.mjs`, and preview API reads require HTTPS and `OBS_ADMIN`.
All private responses use `Cache-Control: no-store`.

GET `/api/v1/obs/previews/{scoreboard|event-log}/{UUID|auto}` returns the existing
exact snapshot or existing AUTO `{ "snapshot": null | exactSnapshot }` envelope.
The staff service uses the existing exact builders and AUTO selector in a
read-only repeatable-read transaction. Confirmed pauses/finish, event history,
retractions, actor ordering and presentation config retain their source contracts.

The private adapter dynamically imports `/auth/staff-session.mjs`, the Backend
reexport of the deployment-frozen Web `/staff-auth-v1.mjs`. The required ABI is
`createStaffSession()`, `bootstrap('OBS_ADMIN')`, `capture()`, `isCurrent(capture)`,
`subscribe(listener)`, `protectedFetch(path, init)`, and `dispose()`.
Deployment must supply that existing Web module with JavaScript MIME and no-store.
No new Web session implementation is included here.

Each page captures once after bootstrap and uses the shared reader and renderers.
Capture loss clears the display and permanently ends that page's polling; late
responses are discarded. Reopening the page is required after a session change.
Pagehide closes its reader, helper subscription, timers, and image work.

Viewer `/api/v1/obs/sources/**` remains bearer-only, including when staff cookies
are present. Default viewer fetches and all image fetches omit cookies. The viewer
entry does not statically import any staff-only module. No public REST/SSE fallback
is introduced. Key-management UI and legacy-source cutover are separate phases.
