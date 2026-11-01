# auth-v1.0.0 freeze record

Backend and Web leads mutually acknowledged the complete wire contract on
2026-11-01. The immutable protocol snapshot is commit
`c48eef30bff9e5e89e34da545b0aaad643e3c993`, `docs/api/auth-contract.md`, SHA256
`d9f83750f91758d95803ee8b06c85072c936eaa95bd4fe5867799e9ce873d5bf`.
Its historical candidate header does not add a further approval hold.

Backend#150 and Web#96 consumers may implement that revision immediately; formal
documentation PR merge, runtime availability and shared QA are separate gates.
Any later semantic change requires a versioned contract callback to consumers.
No implicit latest-file update changes this pinned wire identity.

Backend152 owns core model/security SPI, sessions, accounts, CLI and V11.
Backend153 consumes that SPI for surface policy and secure OBS/docs adapters.
Backend154 consumes installation identity and transport types, owning ingress
wiring/installation-readiness HTTP/upgrade evidence; Web103 owns startup/proxy.
Web106 consumes actual synthetic CLI/API/TLS setup for test infrastructure.
Backend151 release-safety source gate must merge before auth product merges.

Technical availability callbacks go immediately after actual merge, successful
checks and an identified available target to the dependent Web issues and future
Flutter owner (currently PO). This record does not assert implementation readiness,
QA execution, human rollout or permission to alter trust/DNS/credentials.
