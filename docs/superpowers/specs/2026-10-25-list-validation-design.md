# List query validation (#32)

Validate queries for teams, players and matches at the MVC boundary. Typed and
legacy sorting already implement the sorting portion of #32; preserve that contract.

Use Spring Bean Validation for shared pagination (`page >= 0`, `1 <= size <= 100`,
defaults 0/20) and a class-level match date-range constraint (`dateFrom <= dateTo`).
Either date may be omitted and equal boundaries are valid. The size cap of 100
is an implementation default communicated to the user.

Validate supplied scalar pagination/filter parameters for a single nonblank value
and reject Spring form-binding aliases, as sorting already does. Unknown parameters
remain ignored. Preserve valid name searches, UUID/status/date filters and response
shapes. Run the raw scalar guard in scoped @InitBinder hooks, before Spring can
interpret indexed property paths. Invalid types, ranges and ambiguous inputs return 400 ProblemDetail with
code INVALID_REQUEST and an actionable parameter-specific detail.

Use Long arithmetic for pagination offsets, clamped to Int.MAX_VALUE before drop,
so any valid Int page beyond the end returns an empty page without overflow.

Inspection also revealed that MatchServiceImpl ignored both date filters. Apply
inclusive comparisons to plannedStartTimestamp, excluding null timestamps only
when a date boundary is present; tests cover this directly related defect.

Approaches: controller checks would duplicate numeric constraints; a custom MVC
resolver would add unnecessary binding infrastructure. Shared validated request
DTOs and a small scalar-query guard reuse the existing MVC binding and sorting design.
This choice is the implementer's recommendation based on the existing code.

Out of scope: body validation, PATCH migration, database pagination, business rules,
new sorting annotations superseded by the typed sort contract, and GitHub mutations.

Verify through MockMvc contract tests, OpenAPI assertions, existing sorting tests
and the full Gradle suite.
