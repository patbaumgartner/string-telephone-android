# ADR-0003: Messaging architecture — companion API with SQLite persistence

Date: 2026-10-11
Status: Accepted

## Context

One-to-one text messages must survive client restarts, reconnect cleanly,
and be retrievable with delivery state. Options:

1. **SIP MESSAGE through Asterisk** — no persistence, no delivery states,
   no offline queue; authentication story identical to calls but history is
   lost on reconnect and unreadable offline.
2. **Companion API with server-side storage** — persistent, queryable,
   supports idempotent send + cursor-based catch-up sync, ties into the same
   authenticated principal as directory/presence.
3. **Third-party chat backend** — violates the self-hosted/no-vendor rule.

## Decision

Implement messaging in the **companion API** (originally Kotlin/Ktor;
framework superseded by [ADR-0007](0007-managed-spring-admin.md)) with SQLite
storage: server-assigned opaque message IDs, sender identity derived from
the authenticated principal (never from the request body), bounded UTF-8
content, server creation timestamp, client idempotency keys for retry-safe
sends, and cursor pagination. An authenticated WebSocket stream delivers
new-message and presence events; clients catch up by cursor after
reconnect. Protocol documented in `docs/protocol.md`.

Transport encryption (HTTPS/WSS to the self-hosted server) is **not**
end-to-end encryption; the UI and docs must never claim E2EE.

## Consequences

- Message authorisation is enforced server-side from the authenticated
  session; injected sender IDs are ignored.
- Retention/deletion semantics are explicit and tested before any delete
  feature is advertised (v1: no user-initiated remote delete; local cache
  clear only).
- Pushless off-app delivery is best-effort; documented as a limitation.
