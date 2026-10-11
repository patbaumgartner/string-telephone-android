# ADR-0004: Licensing — GPL-3.0-or-later for the Android app, AGPL-3.0-or-later for the server

Date: 2026-10-11
Status: Accepted

## Context

Including liblinphone (GPL-3.0, see ADR-0002) obliges the Android app to be
GPL-compatible. The companion API is a separate program communicating over
a network API; the FSF considers communicating over a network an
"aggregate" relationship, not linking, when the programs are separately
distributed (here: separate artifacts, separate containers). Nonetheless,
both are self-hosted community software by the same author, and the server
could later embed GPL components.

## Decision

- Android app: **GPL-3.0-or-later** (linking linphone-sdk-android).
- Companion API server: **AGPL-3.0-or-later**, so any future hosted/forked
  variant keeps source obligations; the two artifacts remain separate works
  under separate licences.
- Documentation, ADRs, ops tooling: same licence as their component
  (docs dual-viewed as CC-BY-SA where useful; repository-level
  `LICENSE` covers source).
- `NOTICE` and `THIRD_PARTY_NOTICES.md` list linphone-sdk (GPL-3.0),
  AndroidX/Compose (Apache-2.0), OkHttp (Apache-2.0), Ktor (Apache-2.0),
  Asterisk (GPL-2.0, server-side, not distributed to app users), SIPp
  (GPL-2.0, test tooling only).

## Consequences

- App source must carry GPL headers per project convention; no proprietary
  dependency may enter the app module.
- The server's AGPL licence obligates source disclosure for network use —
  satisfied by this public repository.
- Before any v1.0 release, a third-party licence audit regenerates
  `THIRD_PARTY_NOTICES.md` from the actual dependency tree.
