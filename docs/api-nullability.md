# Nullability and partial updates

The raw OpenAPI 3.1 document at `/v3/api-docs` describes nullable values using
JSON Schema types that include `null`. These annotations do not change JSON
serialization or request handling.

`MatchParticipantResponse` and `ParticipantStatisticsResponse` always include
`unknownSlot`, `firstName`, `lastName`, and `number`, including explicit nulls.
`MatchParticipantResponse.photoUrl` is also always present and nullable. Both
PLAYER and UNKNOWN participants use this contract. Other nonnullable required
properties remain required.

For player, team, and match PATCH requests, individual fields may be omitted.
An omitted field leaves its value unchanged. Explicit null clears team `city`
and match `plannedStartTimestamp`. Explicit null is rejected for player
`firstName` / `lastName`, team `name`, and match `teamIds` / `playersPerTeam`.
At least one update must be supplied; an empty PATCH remains a runtime error.

Create-team `city`, create-match `plannedStartTimestamp`, and all fields in the
legacy partial PUT request DTOs accept null. Legacy PUT retains its existing
null/omission handling; it does not gain PATCH clearing semantics. Membership
PUT accepts `number: null` to clear the jersey number (omitting `number` also
uses its existing null default).

Clients can consume the raw schemas directly. In particular, a generated
nullable response property is required, while a PATCH property is optional;
optional does not imply that explicit null is accepted.
