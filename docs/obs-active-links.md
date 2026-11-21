# Active OBS links

The private `/obs-admin.html` page exposes two independent protected source cards
(scoreboard and Event Log), with AUTO or an explicitly opened exact match binding.
Exact selection reuses the existing named match list, with UUID as an alternative.
Opening a context or reopening the page reads existing current links; it never
issues or rotates them. Copy uses only a validated HTTPS URL returned by the
source-key API. Clipboard denial selects the readonly field for manual copy.

Issuance/reissue and revocation require confirmation. Reissue replaces only the
chosen kind/binding, so its previous Browser Source URL must be replaced in OBS.
An ambiguous POST result retains the same operationId in page memory for retry,
including after switching away and back. While unresolved it cannot expose a
supposedly final current URL or revoke key. Explicit HTTP conflicts resolve the
intent; server failures retain it. DELETE captures the shown keyId, then rereads
current, preserving a replacement another tab may already have issued.

The active-links iframe uses `/obs-preview/{scoreboard|event-log}.html` with only
`mode=auto` or `matchId=UUID`. It contains no issued viewer URL/key. AUTO selection
comes from the server and is shown by that F1 preview, including no selected match.
URLs are page-memory only; a captured staff-session change clears them, disables
operations and clears the iframe. The page must be reopened to adopt a new session.

Existing Event Log settings remain global. The separate legacy editor previews
(test card and local scoreboard draft) remain available below; scoreboard saves
remain attached to their explicitly opened match. Changing active-link context
or the server AUTO selection does not change that editor target. Legacy public
source/API retirement, hosted delivery and native OBS acceptance are later work.
