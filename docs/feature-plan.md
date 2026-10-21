# String Telephone — Feature Roadmap

Date: 2026-10-20
Status: In progress (phases 1 to 3 and 5 done; see the table below)

This document captures feature requests, the decisions behind them, and the
planned phases. It is a working document; status updates live in GitHub issues
and the [CHANGELOG](../CHANGELOG.md).

Phases are ordered, named milestones — one PR-sized theme each. There are no
version-numbered releases in this roadmap; a phase is "done" when its
behaviour, tests, protocol documentation and screenshots are merged.

---

## Table of Contents

1. [Phase overview](#planned-phases)
2. [Phase details](#phase-details)
3. [Planned later phases](#planned-later-phases)
4. [Deferred indefinitely](#deferred-indefinitely)
5. [Architecture decisions](#architecture-decisions)
6. [Open decisions](#open-decisions)

---

## Planned phases

Phases land in this order. Each is small enough for one focused review.

| # | Phase | Size | Status | Depends on |
|---|-------|------|--------|------------|
| 1 | Chat scrollbar | Small | Done | — |
| 2 | Bluetooth audio routing | Small | Done | — |
| 3 | PostgreSQL migration | Large | Done | — |
| 4 | Garage media storage (server only) | Medium | Planned | Phase 3 |
| 5 | Read receipts | Medium | Done | Phase 3 |
| 6 | Image sharing | Medium | Planned | Phases 3+4 |
| 7 | Typing indicators | Small | Planned | Phase 3 |
| 8 | Emoji reactions | Medium | Planned | Phase 5 |
| 9 | Reply/quote | Medium | Planned | Phase 5 |
| 10 | Message deletion | Medium | Planned | Phase 5 |
| 11 | Voice messages | Large | Planned | Phase 4 |
| 12 | Video calls | Large | Planned | — |
| 13 | Last-seen & multi-device polish | Medium | Planned | — |
| 14 | Quick reply from notification | Small | Planned | Phase 5 |
| 15 | Conversation list: previews & search | Medium | Planned | Phase 3 |
| 16 | Message search | Medium | Planned | Phase 3 |
| 17 | Message editing | Medium | Planned | Phases 5+10 |
| 18 | Chat list management (pin/mute/archive) | Medium | Planned | — |

Deferred indefinitely: end-to-end encryption, push notifications. A group-chat
scope ADR keeps today's one-to-one designs group-compatible; groups themselves
are a future bet beyond this roadmap's horizon. See
[Deferred indefinitely](#deferred-indefinitely).

---

## Phase details

### Phase 1 — Chat scrollbar

**Effort:** Small
**Status:** Done (2026-10-20)

The chat conversation previously had no scroll indicator. Compose has no
built-in scrollbar for `LazyColumn`, so the chat now draws a thin thumb on
the trailing edge from `listState.layoutInfo` via a reusable `ChatScrollbar`
composable in `UiCommon.kt`. Thumb size and position derive from the live
`LazyListLayoutInfo` (not the pre-computed `rows` list), so the thumb stays
correct across day separators and is proportional to the real scrollable
distance. It appears while scrolling, lingers briefly, then fades; short
conversations (under 8 rows) never show one. The thumb is decorative by
design: the chat auto-pins to the newest message, so a draggable handle
would fight the auto-scroll.

**Tests:** `ScrollbarMetricsTest` (9 geometry cases), `verify-scrollbar.mjs`
composition checks, and the refreshed chat screenshots.

---

### Phase 2 — Bluetooth audio routing

**Effort:** Small
**Status:** Done (2026-10-20)

Calls can now hand audio to a paired Bluetooth headset alongside the existing
speaker and earpiece options. `CallEngine` exposes a three-value `AudioRoute`
(`EARPIECE`, `SPEAKER`, `BLUETOOTH`) plus the set of routes the device
currently offers, and `LinphoneCallEngine` maps each route to liblinphone
output devices (HFP preferred over A2DP for a live call). The engine follows
`onAudioDevicesListUpdated` and mirrors the route liblinphone selected
itself, so the buttons show what the audio actually does for those three
routes — wired headset and USB outputs are deliberately outside this phase's
vocabulary: when one takes over, the buttons keep showing the fallback route
(known limitation). A pure `resolveAudioRoute` rule falls back to the
earpiece when a headset disconnects, takes over when one connects mid-call,
honours an explicit button choice across a transient device-list flap, and
otherwise keeps the user's choice. The call screen renders one button per
available route; the Bluetooth button appears only while a Bluetooth output
exists, and the control row scrolls horizontally so narrow windows never
clip the hang-up button.

**Tests:** `AudioRouteTest` (10 route-selection cases) and refreshed call
screenshots showing all three route buttons, plus the session verification
scripts `verify-audio-route.mjs`, `verify-call-ui-routes.mjs` and
`verify-screenshots.mjs` (all run by `run-session-verify.sh`).

**ADR:** none — the `CallEngine` interface already abstracts audio routing.

---

### Phase 3 — PostgreSQL

**Effort:** Large
**Status:** Done (2026-10-20)

Implements [ADR-0008](adr/0008-database-postgresql.md).

- Persistence moves to PostgreSQL through Spring Boot: an auto-configured
  HikariCP `DataSource` (`spring.datasource.*` from `DB_*` environment
  variables) and Spring's `JdbcClient` replace the hand-rolled `Database`
  class; multi-statement writes run inside narrowly scoped
  `TransactionTemplate` transactions, never across PBX network calls, and all
  statement values are bound with `param()`.
- Explicit forward-only SQL migrations checked into the repository, applied by
  Spring Boot's Flyway auto-configuration at startup (no ORM).
- Compose gains a stock `postgres:18-alpine` service with a named volume and a
  health-gated API startup.
- A **pgweb** container lives in a separate developer overlay
  (`server/compose.dev.yaml`), bound to loopback (never published, never in
  production docs) — same policy as the plain HTTP API port.
- Configuration via `DB_*` environment variables; `API_DB` is removed, and
  `DB_PASSWORD` is mandatory (the API fails startup without it).
- **Fresh install only:** no SQLite import tooling. Existing volumes stay
  untouched; operators start a new database (ADR-0007 semantics).
- Integration tests run against real PostgreSQL via Testcontainers, locally and
  in CI.

**Tests:** API integration tests run on PostgreSQL (Testcontainers); message
idempotency and cursor pagination are verified against the new engine; the
`scripts/verify-postgres.mjs` oracle checks sources, build, configuration,
compose, tests and guidance docs.

---

### Phase 4 — Garage media storage (server only)

Implements [ADR-0009](adr/0009-media-storage-s3.md) with **Garage** as the
prototype-stack default object store. This phase ships storage, upload and
download endpoints; it does not change the chat UI (that is Phase 6).

**Effort:** Medium

- `MediaStore` interface (put/get/delete); first implementation uses the
  AWS S3 SDK v2 against Garage's S3 endpoint. In-memory fake for unit tests;
  Testcontainers Garage for integration tests.
- `media` metadata table in PostgreSQL: id, owner, conversation peer,
  content type, byte size, SHA-256, state, timestamps.
- `POST /v1/media` (authenticated multipart upload) and
  `GET /v1/media/{id}` (participant-checked streaming; no presigned URLs).
- **Limits:** images up to 16 MB; audio up to 50 MB; content-type
  allow-listing; per-type enforcement server-side.
- **Thumbnails:** generated server-side at upload (Java ImageIO; JPEG/PNG).
- **Retention:** indefinite. Nothing is deleted automatically (ADR-0007);
  no TTL job.
- Compose gains a Garage container (single-node dev layout) with its S3
  credentials in `server/.env.example`; production docs describe pointing the
  API at any S3-compatible endpoint.

**Tests:** MediaStore seam with fakes; upload/download authorisation paths
("recipient can fetch, stranger cannot"); size and content-type rejection.

---

### Phase 5 — Read receipts

**Effort:** Medium
**Status:** Done (2026-10-21)

Implements [ADR-0010](adr/0010-message-delivery-states.md).

- Server: `read_at` per message; `POST /v1/conversations/{peer}/read` with
  optional `upToMessageId`; `readAt` field in history responses;
  `message-read` WebSocket event.
- The mark endpoint is called **only** from the foreground chat screen when
  messages are rendered — never from notification handlers, background sync,
  or cursor catch-up.
- **Always on, mutual.** No opt-out; both participants always exchange read
  state. A future off-switch would be a protocol change.
- Client: `SendState` becomes `PENDING / SENT / READ / FAILED` (DELIVERED
  reserved); double tick in brand colour when read.

The server stores the read state in its own column (`read_at`, added by the
`V4__message_read_state.sql` migration) and marks it with a single atomic
`UPDATE`, so a message that is read twice keeps the timestamp of the first
read. The mark derives both sides from the authenticated principal: the
caller is the reader, `{peer}` is the author, and a message outside that
conversation is `404`. The author learns about the receipt through the
`message-read` frame on the realtime stream; the reader already knows from
the response to its own mark. The app keeps a read state sticky, because a
history page fetched before the receipt still says unread.

**Tests:** `ApiIntegrationTest` covers the mutual mark, the idempotence and
the authorization; `ChatCacheTest` and `StringTelephoneRepositoryReadReceiptTest`
cover the receipt semantics, the cursor sync that must not mark read and the
realtime frame; `verify-read-receipts.mjs` checks the contract end to end;
the screenshot `docs/screenshots/10-chat-delivery-states.png` shows every
tick state.

---

### Phase 6 — Image messaging

**Effort:** Medium
**Status:** Done (2026-10-21)

Implements the image variant of the messaging contract on top of the Phase 4
media endpoints ([ADR-0009](adr/0009-media-storage-s3.md)).

- Server: `POST /v1/conversations/{peer}/messages` accepts
  `{"type":"IMAGE","mediaId":...}`; the message carries `type`, `mediaId`,
  `width`/`height` from the upload record. Media upload itself came with
  Phase 4; Phase 6 wires multipart through Tomcat (52 MB file / 54 MB request
  limits, headroom over the 16 MB image cap) and opens `/v1/media/**` on the
  Android bearer chain.
- App: attachment button in the Composer, Photo Picker, client-side
  downscale before upload (max 1920px long edge, JPEG 85, never enlarges —
  `ImageDownscale`), background upload, inline thumbnail from
  `GET /v1/media/{id}/thumbnail` in the bubble, tap to open a fullscreen
  viewer. A quiet placeholder icon renders while no thumbnail is loaded.
- Failed uploads behave like failed text messages: visible failure state,
  tap to resend (resend redelivers the stored `mediaId`).

**Depends on:** Phases 3+4. **Tests:** `ImageDownscaleTest` (Robolectric
compression), `ImageMessagingIntegrationTest` (upload → IMAGE message with
dimensions, both sides' history, stranger and cross-user media rejection,
missing/bogus media), `11-chat-image.png` screenshot with dark variant.

---

### Phase 7 — Typing indicators

**Effort:** Small

Ephemeral WebSocket-only signals, no persistence, no REST:

```json
// Client -> Server
{"type":"typing","payload":{"typing":true}}
// Server -> Recipient (in-memory fan-out, no storage)
{"type":"typing","payload":{"peer":"alice","typing":true}}
```

Client debounces sends (500 ms after last keystroke), UI expires the
indicator after ~5 s without refresh, clears on message sent. Shown in the
chat header presence area.

**Tests:** debounce unit tests; header rendering states.

---

### Phase 8 — Emoji reactions

**Effort:** Medium
Implements the reaction part of [ADR-0011](adr/0011-message-extras.md).

- Any emoji from the keyboard/picker (no curated set).
- Storage: one reactions table keyed by (message, author); API to add/remove;
  fan-out to participants over the existing stream.
- UI: long-press bubble → reaction; count chips under the bubble;
  tap-to-toggle own reaction.

---

### Phase 9 — Reply/quote

**Effort:** Medium
Implements the reply part of [ADR-0011](adr/0011-message-extras.md).

- `replyToId` on the message model; server validates the referenced message
  belongs to the same conversation.
- UI: long-press → reply; quoted snapshot (author + text/thumb) rendered above
  the reply; tap scrolls to the original.

---

### Phase 10 — Message deletion

**Effort:** Medium
Implements the deletion part of [ADR-0011](adr/0011-message-extras.md).

- **Delete for me:** local hide, no server change.
- **Delete for both sides:** soft-delete tombstone synced over the stream;
  bubble renders as a placeholder ("message deleted") on both phones.
- No time window in the first cut (decided; revisit if abuse appears).

---

### Phase 11 — Voice messages (full parity)

**Effort:** Large

Full WhatsApp-style experience on top of the Phase 4 media pipeline:

- Hold-to-record with **lock gesture** (slide up to lock hands-free recording).
- Waveform rendering with **scrubbing**, playback **speed control**,
  **pause/resume**, draft kept if cancelled mid-record.
- Opus audio, 50 MB cap, duration metadata, mic-permission gating consistent
  with calls.

---

### Phase 12 — Video calls

**Effort:** Large

liblinphone supports video; the work is camera capture integration,
video codecs/NAT (the server stack currently manages audio only), call-UI
video surface, camera permissions, and PBX settings for video. Requires its
own review of `server/asterisk/` codec and bandwidth configuration.

---

### Phase 13 — Last-seen and multi-device polish

**Effort:** Medium

- Last-seen timestamps on contacts (privacy-visible, always-on like
  presence today).
- Multi-device hardening: the stream already supports multiple sessions per
  user; this phase covers consistent notification behaviour, unread counts
  and read-state across simultaneously connected devices.

---

### Phase 14 — Quick reply from notification

**Effort:** Small
Depends on: Phase 5 (read receipts share the marking path).

Message notifications currently have no actions; tapping opens the app
(`AppNotifications.kt` has Answer/Decline on call notifications only).

- Add a `RemoteInput` quick-reply action to message notifications: a text
  field directly in the notification shade, sending through the same
  repository path (client key, optimistic append, delivery state) as the
  composer.
- Add a **"Mark as read"** notification action that marks the conversation
  read — explicitly allowed here: the user acted on the notification itself,
  which is a different decision from background sync marking messages read
  behind their back (ADR-0010's rule is about *automatic* marking).
- Sending from the shade must not open the activity; errors surface as a
  follow-up notification ("not sent — tap to retry").

**Tests:** notification action unit tests (Robolectric), RemoteInput round-trip
with the mocked repository.

---

### Phase 15 — Conversation list: previews and search

**Effort:** Medium
Depends on Phase 3 (PostgreSQL).

The contacts screen today shows only a name and unread badge. WhatsApp/Signal
style rows carry a last-message snippet and timestamp.

- Last-message preview per conversation: `ChatCache` gains a preview accessor
  (text or media placeholder); repository exposes a per-peer preview flow.
- Relative timestamp on each row (today → time, this week → weekday, older →
  date), matching the DayChip label logic already in the chat.
- Tombstoned messages preview as "message deleted" (Phase 10); replies
  preview the reply text (ADR-0011 preview rules).
- A search bar filters conversations by name server-side or client-side on
  the loaded contact list (local filtering is enough at the expected scale).

**Tests:** preview derivation unit tests; screenshot with unread badges and
previews.

---

### Phase 16 — Message search

**Effort:** Medium
Depends on Phase 3 (PostgreSQL full-text search).

- Server: `GET /v1/search?peer=&q=` over the messages table using PostgreSQL
  full-text search (tsvector column + GIN index added by migration); scoped
  to the authenticated pair's conversation, paginated like history.
- App: search entry in the chat header; results as a list, tap scrolls the
  LazyColumn to the message (jump by index found in `rows`).
- Case: the server stores raw text; diacritics-insensitive matching via
  `unaccent`/`simple` config is a migration-time decision, documented then.

**Tests:** server search endpoint tests (match, no-match, other-user access
denied); app scroll-to-result logic unit tests.

---

### Phase 17 — Message editing

**Effort:** Medium
Depends on: Phase 5 (message state plumbing) and Phase 10 (tombstones share
the edit-state sync path).

- Server: `edited_at` on the message record; `PUT /v1/conversations/{peer}/
  messages/{id}` re-stores content with the same idempotency rules;
  `message-edited` WebSocket event. Only the author may edit.
- No time window in the first cut (consistent with deletion's no-window
  decision; ADR-0011 pattern).
- Client: own messages get an edit action; edited bubbles show an "edited"
  hint next to the time; the content description announces it.
- Deletes take precedence: editing a tombstoned message is a `409`.

**Tests:** edit + sync round-trip, edit-then-read interaction, screenshot of
the edited hint.

---

### Phase 18 — Chat list management (pin/mute/archive)

**Effort:** Medium

Conversation-level state, stored per user (the server stores it on the user's
record so all devices agree):

- **Pin:** up to a handful of conversations pinned at the top of the list.
- **Mute:** suppress notifications for the conversation; unread badges stay.
- **Archive:** hide from the main list behind an "Archived" entry; a new
  incoming message un-archives (WhatsApp behaviour).
- REST surface: a user-conversation-state table (`username, peer, pinned,
  muted, archived`) with one update endpoint; the app applies filters locally
  from synced state.

**Tests:** state-transition unit tests; list-ordering tests; screenshot with
pinned/muted/archived states.

---

## Deferred indefinitely

| Feature | Reason |
|---------|--------|
| **End-to-end encryption** | Signal Protocol changes client, server and protocol fundamentally. Would need its own ADR before any work; revisit only when there is demand. |
| **Push notifications (FCM)** | Requires Google Play Services, conflicting with the self-hosted rule; would need its own ADR. Background delivery remains documented as a limitation. |
| **Group chats** | The single largest structural gap versus WhatsApp/Signal, touching schema (membership), protocol (fan-out, per-member delivery), PBX (conference bridge) and UI. Kept out of this roadmap's horizon — but [ADR-0012](adr/0012-group-chat-scope.md) records compatibility constraints today's one-to-one designs must keep so a later group phase does not force rework of read receipts, reactions or deletion. |

---

## Architecture decisions

| ADR | Area | Status |
|-----|------|--------|
| [ADR-0008](adr/0008-database-postgresql.md) | PostgreSQL 18 migration | Accepted (supersedes SQLite from ADR-0003/0007) |
| [ADR-0009](adr/0009-media-storage-s3.md) | Media storage — Garage default, S3-compatible interface | Accepted |
| [ADR-0010](adr/0010-message-delivery-states.md) | Read receipts (always-on) | Accepted |
| [ADR-0011](adr/0011-message-extras.md) | Reactions, replies, deletion semantics | Accepted (planned) |
| [ADR-0012](adr/0012-group-chat-scope.md) | Group chats — scope and one-to-one compatibility constraints | Accepted (scope only; implementation deferred) |

---

## Open decisions

Remaining small choices, resolved during the phase that needs them:

1. **pgweb binding details** — decided in Phase 3: a separate developer
   overlay (`server/compose.dev.yaml`), no compose profile, loopback only.
2. **HikariCP sizing** — resolved in Phase 3: Spring Boot's defaults (pool
   max 10) are enough for the prototype; tune `spring.datasource.hikari.*`
   and document it in `docs/operations.md` once real load is measured.
3. **Video call server-side settings** — codec and NAT configuration review
   happens in Phase 12, before any client work.
4. **Search text normalisation** — diacritics/case folding config for the
   PostgreSQL tsvector column is decided in the Phase 16 migration.
5. **Pin limit count** — how many conversations can be pinned at once is a
   Phase 18 UI decision.

---

## Related documents

- [Architecture](architecture.md) — module boundaries and data flow
- [Protocol](protocol.md) — current API and WebSocket contracts
- [Operations](operations.md) — deployment and configuration
- [ADRs](adr/) — architecture decision records