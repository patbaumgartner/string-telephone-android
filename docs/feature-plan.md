# String Telephone — Feature Roadmap

Date: 2026-10-25
Status: In progress (phases 1 to 3, 5 to 11 done; see the table below)

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
| 4 | Garage media storage (server only) | Medium | Done | Phase 3 |
| 5 | Read receipts | Medium | Done | Phase 3 |
| 6 | Image sharing | Medium | Done | Phases 3+4 |
| 7 | Typing indicators | Small | Done | Phase 3 |
| 8 | Emoji reactions | Medium | Done | Phase 5 |
| 9 | Reply/quote | Medium | Done | Phase 5 |
| 10 | Message deletion | Medium | Done | Phase 5 |
| 11 | Voice messages | Large | Done (2026-10-26) | Phase 4 |
| 12 | Video calls | Large | Done (2026-10-27) | — |
| 13 | Last-seen & multi-device polish | Medium | Done (2026-10-28) | `V10__last_seen.sql` |
| 14 | Quick reply from notification | Small | Done (2026-10-28) | — |
| 15 | Conversation list: previews & search | Medium | Done (2026-10-28) | — |
| 16 | Message search | Medium | Done (2026-10-28) | `V11__message_search.sql` |
| 17 | Message editing | Medium | Done (2026-10-30) | `V12__message_editing.sql` |
| 18 | Chat list management (pin/mute/archive) | Medium | Done (2026-10-30) | `V13__conversation_state.sql` |
| 19 | Group chats | Large | Planned | Phase 3 |

Deferred indefinitely: end-to-end encryption, push notifications. The group-chat
scope is captured in [ADR-0012](adr/0012-group-chat-scope.md); this roadmap now
includes a Phase 19 implementation. See [Phase 19](#phase-19--group-chats).

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

**Effort:** Medium
**Status:** Done (2026-10-21)

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
**Status:** Done (2026-10-25)

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
**Status:** Done (2026-10-25)
Implements the reaction part of [ADR-0011](adr/0011-message-extras.md).

- Any emoji from the keyboard/picker (no curated set).
- Storage: one reactions table keyed by (message, author); API to add/remove;
  fan-out to participants over the existing stream.
- UI: long-press bubble → reaction; count chips under the bubble;
  tap-to-toggle own reaction.

**Done 2026-10-25.** Reactions table (V6) keyed by (message, author) with
upsert semantics; `PUT`/`DELETE` reaction endpoints return the post-change
list; `message-reaction` frames fan out to both participants; history pages
embed each message's reactions. The app shows count chips under the bubble,
long-press opens a quick-emoji picker, and tapping a chip toggles the user's
own reaction optimistically (rolled back if the request fails). Failed
messages (no server id) cannot be reacted to. Oracle:
`scripts/verify-reactions.mjs`.

---

### Phase 9 — Reply/quote

**Effort:** Medium
**Status:** Done (2026-10-25)
Implements the reply part of [ADR-0011](adr/0011-message-extras.md).

- `replyToId` on the message model; server validates the referenced message
  belongs to the same conversation.
- UI: long-press → reply; quoted snapshot (author + text/thumb) rendered above
  the reply; tap scrolls to the original.

**Done 2026-10-25.** A nullable `reply_to` column (V7) references the quoted
message; the send endpoint validates that the target exists and belongs to the
same conversation (`400` otherwise). History responses and the `new-message`
frame carry `replyToId`. Long-press opens a reply/reaction sheet; choosing
Reply shows a quoted banner above the composer, and the reply bubble renders a
snapshot (author + text or photo preview) resolved from the loaded history.
Scroll-to-original is deferred; the quoted snapshot renders only when the
original is in the cache. Oracle: `scripts/verify-reply.mjs`.

---

### Phase 10 — Message deletion

**Effort:** Medium
**Status:** Done (2026-10-25)
Implements the deletion part of [ADR-0011](adr/0011-message-extras.md).

- **Delete for me:** local hide, no server change.
- **Delete for both sides:** soft-delete tombstone synced over the stream;
  bubble renders as a placeholder ("message deleted") on both phones.
- No time window in the first cut (decided; revisit if abuse appears).

**Done 2026-10-25.** V8 migration adds nullable `deleted_at`/`deleted_by` to
`messages`; `DELETE /v1/conversations/{peer}/messages/{messageId}` clears the
content, stamps the tombstone (row retained, so ids and reply references stay
stable) and fans a `message-deleted` frame to both participants. The response
and history pages carry the tombstone shape; the call is idempotent. The
long-press sheet gains Delete with two scopes: "for me" (client-local hide,
no server change) and "for both" (only when the message has a server id);
deleted bubbles render an italic "Message deleted" placeholder on both sides,
content stays in the local record so reply quotes still render. Optimistic
tombstone with rollback and history re-fetch on failure. Oracle:
`scripts/verify-deletion.mjs`.

---

### Phase 11 — Voice messages (full parity)

**Effort:** Large
**Status:** Done (2026-10-26)

Implements voice messages on top of the Phase 4 media pipeline.

**Recording:**
- Hold-to-record with MediaRecorder. API 29+ records Opus in OGG container
  (`.ogg`), API 26–28 falls back to AAC in M4A (`.m4a`). The UI uses the
  correct extension per codec. Max size: 50 MB (enforced server-side).
- Mic-permission gate lives inside the mic button's pointer-gesture handler;
  permission is requested only when the user first touches the button.
- Slide up > 140 px to lock recording hands-free. Cancel by sliding left > 90 px
  while unlocked, or with the bar's cancel button once locked.
- Draft file deleted on cancel, on error, and after the bytes are read.

**UI:** `MicRecordButton` owns the press-and-hold gesture and stays composed
for the whole take — detaching it would cancel the pointer handler and swallow
release-to-send. `VoiceRecordingBar` takes the text field's place and shows
the timer, a live waveform, and cancel/send; a locked take shows the lock
marker instead and ends on one of those buttons. `VoiceBubbleContent` renders
a waveform, duration, play/pause, speed-control chips (0.5×/0.75×/1×/1.5×/2×)
and tap-to-scrub. Leaving the chat, or the app going to the background (Android
hands it silence, not microphone input), cancels an in-flight take as well as
stopping playback. Waveform persisted on the message as comma-separated
integer amplitudes (≤ 64 samples, each clamped to 0–100 server-side).

**Playback:** `VoicePlayer` wraps `MediaPlayer` with `PlaybackParams` speed,
pause/resume, and seek. Playback speed cycles through the 5 preset values.
One player serves the whole chat, so its state names the media it belongs to
and only that bubble offers controls and a progress fill.

**Seams:** `MediaRecorderVoiceRecorder` (Android framework) and
`MediaPlayerVoicePlayer` are implementation classes; `VoiceRecorder` /
`VoicePlayer` are the tested interfaces. `FakeCallEngine` seeds VOICE_CHAT
conversations for screenshot and UI tests.

**ADR:** no new ADR needed — voice uses the existing media-type pipeline
defined by ADR-0009 and the extras pattern from ADR-0011.

**Tests:** `VoiceMessageTest` (format duration, normalised waveform, speed
labels), `ChatCacheTest` voice round-trip, `RealtimeEventTest` voice frame,
`FakeCallEngine` VOICE_CHAT seed. Oracle: `scripts/verify-voice.mjs`.
Screenshots: `15-chat-voice.png` (light) and `dark-chat-voice.png`.

---

### Phase 12 — Video calls

**Effort:** Large
**Status:** Done (2026-10-27)

Plan written 2026-10-26 and confirmed by the maintainer: full-parity
bidirectional video, VP8 only, Asterisk-anchored media, camera used only while
the call screen is visible.

**Decisions (captured as [ADR-0013](adr/0013-video-calls.md)):**

- **Asterisk stays in the media path.** `direct_media=no` is already set, so a
  video call has one NAT surface (`external_media_address` plus
  `local_net` when configured) and no dependence on ICE. No ICE on the Asterisk
  side: liblinphone's candidates are ignored and the anchored path carries the
  media. The cost is that video bandwidth flows through the server; that is
  accepted for a self-hosted deployment and is why the setting can be turned
  off.
- **VP8 only.** Every liblinphone Android build ships VP8, Asterisk 22 passes
  it through as a plain codec, and there is no royalty surface. H264 (hardware
  decode) and AV1 are follow-ups, not part of this phase.
- **Camera only while the call screen is in the foreground.** Leaving the app
  re-negotiates the call to audio-only; audio keeps running under the existing
  microphone foreground service. No `camera` foreground-service type and no
  `FOREGROUND_SERVICE_CAMERA` permission, so there is never a hidden camera.
  ADR-0006 gains an amendment; the microphone type and the no-Telecom stance
  stay as they are.
- **"Camera off" keeps the peer visible.** The in-call camera button
  re-INVITEs with `videoDirection = RecvOnly` (stop sending, keep watching)
  rather than tearing the stream down; only backgrounding removes video
  entirely.
- **SRTP over TLS stays mandatory.** Video is not a reason to relax the call
  security settings (`media_encryption=sdes`, `verifyServerCertificates`,
  `setCapabilityNegociationEnabled(false)`).

**Server first (open decision 3: before any client work):**

- `PbxSettings` gains `video: String = "vp8"`, allowed values `vp8` and
  `none`. `validate()` rejects anything else and the endpoint template appends
  it to `allow=` (audio order untouched, video last). The settings row is
  JSON-backed, so a defaulted field is backward compatible without a Flyway
  migration.
- Admin form gains a "Video codec" select bound to the same value; the
  managed admin template, `Admin.kt`, and `test-admin-ui.mjs` move together.
- Bandwidth and port review: a video call needs four RTP/RTCP ports (audio and
  video) and roughly 0.5-1.5 Mbit/s per direction at 640x480. The
  `10000-10100` range and the Compose UDP export stay as they are (101 ports
  still allow ~25 concurrent video calls); `docs/operations.md` gains the
  sizing note and the firewall guidance.
- Prototype proof: the caller offers `m=video … RTP/AVP 96` with
  `a=rtpmap:96 VP8/90000` and the `200 OK` keeps the video stream active with
  VP8 negotiated; a negative step proves a codec outside the allow-list
  (`vp9`) is answered with `m=video 0` instead of a broken call.

**App — engine seam (no SDK types outside `call/`):**

- `CallEngine` gains `start(peer, withVideo)`, `setVideoEnabled(enabled)` and
  `switchCamera()`. `CallSessionInfo` gains `videoOffered`, `videoEnabled`,
  `videoActive` and `cameraFacing`.
- Video policy lives in pure functions next to `resolveAudioRoute` and
  `callFailureFor`, so unit tests cover it without liblinphone: start with or
  without video, what to do when the call screen is backgrounded, cycle the
  camera, pick the default audio route (speaker unless Bluetooth is connected),
  and fall back to audio-only when camera permission is denied.
- `LinphoneCallEngine` uses the SDK surface verified against the 5.4.88 AAR on
  2026-10-26: `core.setVideoCaptureEnabled`/`setVideoDisplayEnabled`, the
  remote and self-view surfaces through `core.setNativeVideoWindowId` and
  `setNativePreviewWindowId`, `call.update(params)` for every re-INVITE,
  `CallParams.setVideoEnabled` for on/off and `setVideoDirection`
  (`SendRecv` while sending, `RecvOnly` for camera off), `core.videoDevicesList`
  plus `core.setVideoDevice` for the flip, and `PayloadType.normalBitrate` to
  cap the video bitrate. There is no dedicated video-state listener in this
  SDK build, so "video active" is derived from the `StreamsRunning` call params
  (`usedVideoPayloadType`, `videoDirection`) plus
  `call.requestNotifyNextVideoFrameDecoded()` for the first frame.

**App — UI:**

- The chat header gains a video-call button next to the phone button; placing
  a call from it invites with video. An audio call keeps the existing path.
- `CallScreen` renders the remote stream full-bleed with the peer name, timer
  and controls overlaid, and the local self-view as a draggable picture-in-
  picture. Controls gain camera on/off and camera flip in the existing
  `CircleAction` row; with no negotiated video the screen keeps its current
  audio layout.
- The camera permission gate mirrors the microphone gate: requested on the
  first video action, and a denial falls back to audio-only with an inline
  notice plus a link to app settings (the recovery pattern already used for
  microphone denial).
- Background policy: `MainActivity.onStop` re-negotiates to audio-only (the
  same hook that now cancels a voice take). Returning does not silently
  re-enable the camera; the call screen shows "Camera off" until the user
  turns it back on.
- The screen stays awake while a video call is active, and incoming/missed
  call notifications say "video" when the INVITE carried a video stream.

**Tests and proof:**

- Unit: the video policy functions; `CameraPermissionTest` (Robolectric)
  mirroring `MicrophonePermissionTest`.
- Screenshots: `16-call-video.png` plus `dark-call-video.png` from a fake
  engine with video state. Robolectric cannot decode video, so the fixture
  draws a deterministic placeholder in the surface slot; the oracle asserts
  the real `SurfaceView` wiring separately.
- Server: `AsteriskConfig`/`PbxGatewayTest` assertions for
  `allow=opus,ulaw,vp8`, rejection of unknown codecs, and the admin round-trip.
- Live: the prototype step above plus a maintainer checklist on a device and a
  second device/emulator (send and receive, flip, on/off mid-call,
  backgrounding, an audio-only peer, denied camera permission). Emulator
  cameras give a synthetic scene, so the checklist is the real gate for
  hardware.
- Oracle: `scripts/verify-video-calls.mjs` (engine API, surface wiring,
  permission gate, background downgrade, notification wording, server setting
  and admin form, prototype step, docs and ADR, no dead strings), appended to
  `scripts/run-session-verify.sh`.

**Gates:** G1 oracle, G2 `:core:test` + `:app:testDebugUnitTest`, G3 lint +
both assembles + `:server:api:installDist`, G3b
`:server:api:compileTestKotlin`, G4 static gates, G5 all oracles plus
`verify-plan-status.mjs 1 2 3 5 6 7 8 9 10 11 12`, G6 helper suites, G7 the two
new screenshots against `app/build/screenshots`, G8 docs plus one commit.
G9 (`:server:api:test`) and G10 (`scripts/test-prototype.sh`) both need Docker
and stay CI/maintainer hand-offs, as in Phases 9-11.

**Risks:**

1. Video surfaces and camera behaviour differ per device; an emulator-only
   pass can hide hardware problems, so the manual checklist is not optional.
2. SDES SRTP with VP8 through Asterisk is the least-exercised path in this
   stack. If it fails, fix it in the PBX configuration; never downgrade the
   encryption policy to get video working.
3. Video through Asterisk doubles CPU and bandwidth. The setting defaults to
   `vp8` for parity but an operator can set `none`; the operations note makes
   the cost explicit.
4. Robolectric cannot render video, so the screenshot is a fixture and the
   prototype is the real proof of the media path.

**Done 2026-10-27.** `PbxSettings.video` (`vp8`/`none`) is validated, appended
last to the endpoint `allow=` list, exposed in the admin form and the managed
admin helper, and covered by `PbxGatewayTest` (VP8 negotiated, unknown codecs
rejected, `none` removes the payload). The prototype gains a `G3b` step whose
caller offers VP8 (answered `VIDEO-NEGOTIATED`) and VP9 (answered
`VIDEO-REJECTED`, i.e. `m=video 0`). On the client, `CallEngine` gains
`call(peer, withVideo)`, `accept(withVideo)`, `setVideoEnabled`,
`releaseVideo`, `switchCamera` and `attachVideoSurfaces`, plus pure video
policy (`startWithVideo`, `afterCallScreenHidden`, `nextCameraFacing`,
`defaultAudioRoute`). `LinphoneCallEngine` enables only VP8, caps its bitrate,
derives `videoActive` from `StreamsRunning` params, uses
`setNativeVideoWindowId`/`setNativePreviewWindowId` for both surfaces, and
re-INVITEs `SendRecv`/`RecvOnly`/disabled for camera on, camera off and
backgrounding. The chat header has a video button; `CallScreen` renders the
remote stream full-bleed with a draggable self-view and camera/flip controls;
the camera permission gate falls back to audio. `MainActivity.onStop` drops
video while audio continues, and incoming-call notifications say "video".
Tests: `VideoCallPolicyTest`, `CameraPermissionTest`; screenshots
`16-call-video.png` and `dark-call-video.png`. Oracle:
`scripts/verify-video-calls.mjs`, appended to `scripts/run-session-verify.sh`.
Docs: ADR-0013, ADR-0006 amendment, protocol call section, operations sizing.
Manual device/emulator checklist (send, receive, flip, mid-call on/off,
backgrounding, audio-only peer, denied permission) remains the hardware gate.

---

### Phase 13 — Last-seen and multi-device polish

**Effort:** Medium
**Status:** Done (2026-10-28)

Plan written 2026-10-27. Two strands, both small and testable:

**Last-seen timestamps (server + app).** The server already derives
`presence` from two observations (open realtime stream, registered SIP
contact) and reports `presenceUpdatedAtEpochMs` — when that state was
*observed*, which resets on every poll and means nothing to a human. Phase 13
adds a durable **last-seen** per user:

- **Server:** V10 migration adds `users.last_seen_at BIGINT` (nullable). The
  value is written when a user's last realtime session closes (WsHub
  `afterConnectionClosed` → presence hook) and on sign-out/disable — wall
  clock from the existing `clock` seam. It is *not* touched on every API
  request: last-seen means "was last reachable", not "last clicked".
- **Wire:** `GET /v1/contacts` gains `lastSeenEpochMs` per contact (null when
  they have never connected or are currently online); the `presence` realtime
  frame carries it too (`lastSeenEpochMs`), so an offline transition delivers
  the timestamp push — no polling.
- **App:** an offline contact's row subtitle shows "last seen <relative>"
  instead of plain "Offline", using the same recency buckets as `dayLabel`
  (today → time, this week → weekday, older → date). Online stays green-dot
  "Online"; unknown stays "Unknown". No settings, no opt-out — mirrors the
  always-on presence and read-receipt decisions (ADR-0010's mutuality logic).
- **Privacy stance:** last-seen is derived from connection state only, has
  minute-level granularity (truncated server-side to the minute), and never
  distinguishes app-open from merely-registered — same visibility as today's
  presence dot, just with a timestamp.

- **Multi-device hardening (no protocol change):** the registry already
  fans out per user; this phase proves and fixes the seams:
  - `markRead` fan-out currently notifies only the *author* (`send(peer)`);
    the reader's **other** devices learn the new read state only via their
    own mark response. Fix: broadcast the read event to **both** sides, with
    the reader's own devices applying it idempotently (read receipt
    application is already idempotent) so unread badges converge.
  - Unread badge consistency: unread counts are derived locally; with two
    devices, a conversation opened on device A leaves device B's badge until
    its next `message-read` frame — the both-sides broadcast above fixes
    exactly that.
  - Notification behaviour: notifications are posted by whichever device is
    backgrounded (already correct — active-conversation suppression is
    per-device by design); a device that marks read while another is
    notifying gets the cancellation through its own `message-read` handler —
    add cancel-on-receipt for the affected peer's notification.
  - Document the model in `docs/protocol.md`: multiple streams per user are
    supported, events fan out to all of a user's sessions, and last-seen
    means the last disconnection of the final session.

**Tests:** Presence last-seen unit tests (offline path stamps, online path
clears, idempotent close); integration test: contacts carry `lastSeenEpochMs`
after disconnect, realtime `presence` frame carries it, second device
receives `message-read` for its own unread messages; app test: presence event
with `lastSeenEpochMs` renders the relative label; screenshot of the contacts
list with an offline "last seen" row.

**Depends on:** nothing new (presence + receipts exist).

Shipped: V10 migration, `Users.markLastSeen`/`clearLastSeen`/
`truncateToMinute`, WsHub stamp-on-final-disconnect / clear-on-connect,
disable stamps in the same UPDATE, `lastSeenEpochMs` on contacts and in the
`presence` frame, `LastSeenLabel` buckets, contacts subtitle, both-sides
`message-read` fan-out, `readElsewhere` notification cancellation.
Screenshots: `17-contacts-last-seen.png` and `dark-contacts-last-seen.png`
(offline Carol with a fixed June 2025 stamp so the DATE bucket is
deterministic). Oracle: `scripts/verify-last-seen.mjs`, appended to
`scripts/run-session-verify.sh`. Docs: protocol contacts + presence frame +
multi-device fan-out, CHANGELOG.

---

### Phase 14 — Quick reply from notification

**Effort:** Small
**Status:** Done (2026-10-28)
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

**Depends on:** Phase 5 (read receipts share the marking path).

Shipped: `MessageActionReceiver` (RemoteInput extraction → repository send,
"Mark as read" → `markConversationRead(peer, null)`, failure follow-up
notification on the message channel, never starts an activity), conversation
notification gains both actions (semantic actions, no UI), receiver registered
non-exported in the manifest, five new strings, ADR-0010 amended (the shade
action is an explicit user act). Tests: `MessageActionReceiverTest` (6
Robolectric tests over the real repository and container). Oracle:
`scripts/verify-quick-reply.mjs`, appended to `scripts/run-session-verify.sh`.
Docs: ADR-0010 marking-read paragraph, feature-plan status, CHANGELOG.

---

### Phase 15 — Conversation list: previews and search

**Effort:** Medium
**Status:** Done (2026-10-28)
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

**Depends on:** Phase 3 (PostgreSQL).

Shipped: `ChatCache.preview`/`peers` accessors (framework-free, `changes`
gains `replay = 64` so a late-subscribing collector sees the last mutation),
repository `previews: StateFlow<Map<String, ChatMessage>>` mirroring the
newest message per peer (removed when the last message is hidden locally,
cleared on sign-out), `filterContacts` (case-insensitive display-name +
username match), `ContactsScreen` rows showing preview + relative timestamp
(`PreviewTimeLabel` reusing the day-chip buckets) with the presence/last-seen
fallback for message-less contacts, search field with clear button,
`ic_search` drawable. Tests: `ChatCachePreviewTest` (5),
`StringTelephoneRepositoryPreviewTest` (6), `PreviewTimeTest` (7),
`ScreenshotTest` 18-contacts-previews (light + dark; fixed June 2025 stamps
so the DATE bucket renders deterministically). Oracle:
`scripts/verify-previews.mjs`, appended to `scripts/run-session-verify.sh`.
Docs: feature-plan status, CHANGELOG.

---

### Phase 16 — Message search

**Effort:** Medium
**Status:** Done (2026-10-28)
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

**Depends on:** Phase 3 (PostgreSQL full-text search).

Shipped: V11 migration (generated `search_text` tsvector over `content`,
`simple` config — case-folded, diacritics kept; GIN index), `Messages.search`
(scoped like history, `websearch_to_tsquery`, history-cursor pagination,
blank-query guard), `GET /v1/conversations/{peer}/search` endpoint,
exception handler maps missing/malformed params to `400` instead of `500`,
ApiClient `search`, repository one-shot paginated search, ViewModel
search state (cancel-previous, close resets), chat header search toggle +
`ChatSearchBar` + `ChatSearchResults`, `timelineIndexOf` scroll-to-result
via `LazyListState.animateScrollToItem`. Search text normalisation (open
decision 4) resolved: `simple`, documented in the V11 migration and
protocol.md. Tests: server 43 (+2 integration: scope/no-match/blank/404 and
cursor pagination), app 190 (+3 `ChatTimelineIndexTest`). Docs: protocol
search section, feature-plan status, CHANGELOG.

---

### Phase 17 — Message editing

**Effort:** Medium
**Status:** Done (2026-10-30)
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

Shipped: V12 migration (`edited_at` BIGINT, null when never edited),
`Messages.edit` (author-guarded UPDATE that preserves the read receipt and
refuses tombstones), `PUT /v1/conversations/{peer}/messages/{id}` endpoint
(author-only `403`, tombstone/non-text `409`, blank `400`, unknown `404`),
`message-edited` WS frame fanned to both participants, core `MessageDto`/
`WsMessageEditedDto`/`RealtimeEvent.MessageEdited` plumbing, `ApiClient
.editMessage`, repository edit (optimistic content swap with rollback +
history reload on failure) and realtime edit handler, `ChatMessage
.editedAtEpochMs`/`canEdit`, `ChatCache.applyEdited`, ViewModel edit mode
(`startEditing`/`cancelEditing`, send-applies-edit, draft preserved,
no typing signal while editing), edit banner above the composer, Edit
action in the long-press sheet (author/text-only/non-deleted), "edited"
hint next to the time with a content-description announcement.
Screenshot: `16-chat-edited.png` + dark variant.

---

### Phase 18 — Chat list management (pin/mute/archive)

**Effort:** Medium
**Status:** Done (2026-10-30)

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

Shipped: V13 migration (`user_conversation_state`, PK username+peer,
all flags default false), `ConversationStates` store (lazy upsert, pin cap
5 via 400, `unarchive` used by the send-path fan-out so an incoming
message un-archives for the recipient), `GET`/`PUT
/v1/conversations/{peer}/state`, contacts rows enriched with the caller's
own `pinned`/`muted`/`archived`, core `ConversationStateDto` +
`UpdateConversationStateRequest` + extended `ContactDto`, ApiClient
`conversationState`/`updateConversationState`, repository state map synced
from contacts, optimistic update with rollback, client-side un-archive on
incoming, `orderedContacts` (pinned top preserving order, archived out),
ViewModel exposure, contacts UI: long-press options sheet (pin/mute/
archive toggles), pin + mute indicators on rows, "Archived" section,
mute gate in SessionController (no shade for muted peers, badges stay).
Screenshot: `19-contacts-managed.png` + dark variant.

---

### Phase 19 — Group chats

**Effort:** Large
**Status:** Done (2026-10-31)
**Depends on:** Phase 3 (PostgreSQL — membership and group message schema)

Implements the group-chat feature as scoped by
[ADR-0012](adr/0012-group-chat-scope.md). The schema, protocol and UI extend
the one-to-one model rather than redesigning it.

#### Schema (Flyway migrations)

- **`groups`** table: `id` (UUID PK), `name`, `creator` (username FK),
  `created_at`, `updated_at`. Each group has exactly one admin who creates it.
- **`group_members`** table: `group_id` (FK → `groups.id`), `username` (FK →
  `users.username`), `role` (`ADMIN | MEMBER`), `joined_at`. Composite PK
  `(group_id, username)`.
- **`messages`** gains a nullable `group_id` column (FK → `groups.id`);
  when set, `peer`/`recipient` are null and the message is visible to all
  group members. A `CHECK` ensures a message is either pairwise (`peer` set)
  or group-level (`group_id` set), never both.
- **Per-member read state:** a new `group_message_reads` table keyed by
  `(message_id, username)` with `read_at TIMESTAMPTZ`. The existing
  `messages.read_at` column is retired; Phase 5's read-receipt logic is
  updated to query this new table for group messages.

#### Server API

- **Group management:**
  - `POST /v1/groups` — create a group (name, initial members). Creator is
    automatically added as ADMIN. Returns the group object with its members.
  - `GET /v1/groups` — list groups the authenticated user belongs to.
  - `GET /v1/groups/{id}` — group details + members (ADMIN or MEMBER only).
  - `PUT /v1/groups/{id}` — update name (ADMIN only).
  - `POST /v1/groups/{id}/members` — add a member (ADMIN only).
  - `DELETE /v1/groups/{id}/members/{username}` — remove a member (ADMIN
    removes others; MEMBER removes themselves = leave).
  - `DELETE /v1/groups/{id}` — disband the group (ADMIN only); cascades to
    messages and membership; existing calls are left running until the PBX
    retry loop catches the `403`.

- **Group messaging:**
  - `POST /v1/groups/{id}/messages` — send a group message. Authorship is
    derived from the principal; `403` if the caller is not a member.
    Fan-out is stored (one row per message, N recipients read it).
  - `GET /v1/groups/{id}/messages` — paginated history for the group
    (cursor-based, like pairwise history). Includes reactions and reply
    metadata per message, scoped to the caller's group membership.
  - `POST /v1/groups/{id}/read` — mark messages read up to a given ID
    (one row per message in `group_message_reads`).

- **WebSocket events:**
  - `group-message` — fan-out to all members when a group message is stored.
  - `group-message-read` — per-member read fan-out (each member sees only
    their own reads; no all-read-in-group event in the first cut).
  - `group-reaction`, `group-reply`, `group-deleted` — mirror the pairwise
    `message-reaction` / `message-deleted` envelope with a `groupId` field.
  - `typing` gains a `groupId` variant: `{"type":"typing","payload":
    {"groupId":"...","typing":true}}`.

#### PBX (Asterisk)

- Group calls use Asterisk **confbridge**. The API manages conference rooms
  dynamically: when the first member initiates a call, a conference bridge is
  created and the PBX dialplan is extended; when the last member leaves, the
  bridge is torn down.
- SIP REGISTER and INVITE flows remain unchanged; only the call target
  changes from a pairwise SIP URI to a conference bridge.
- Existing pairwise calls are unaffected.

#### App UI

- **New screen:** Group list (analogous to the contacts/conversation list).
  FAB to create a group; each row shows group name, member count, last
  message preview.
- **Group detail screen:** member list, add/remove members (ADMIN only),
  leave/disband actions.
- **Group chat screen:** identical layout to pairwise chat; the header shows
  the group name and member avatars. Typing indicators fan out within the
  group.
- **Read receipts in groups:** rendered as a counter badge ("3 read") on
  sent messages, not per-member avatars — the first cut shows only "who read"
  in the group detail, not in the chat bubble.
- **Notifications:** grouped by group name; the notification body shows
  the sender name and message snippet.

**Tests:** Group creation/membership/leave integration tests; fan-out
verification; read-receipt per-member correctness; confbridge lifecycle in
the live prototype (`scripts/test-protocol.sh`); screenshot of group list
and group chat screens.

Shipped: V14 migration (`V14__group_chats.sql`: `groups`, `group_members` with role CHECK,
`messages.group_id`, per-kind partial unique indexes for idempotency,
`group_message_reads` per-member reads), `Groups` store (creator-ADMIN
creation, 32-member cap, idempotent add, admin-removes-others / member-
leaves / admin-disbands rules), group REST endpoints (create → 201, list,
details, rename, add/remove member, leave, disband, send → 413 on
empty/oversize, cursor history with `readCount`, per-member read mark, group
reactions, author-or-admin delete; non-members get 404 everywhere, pairwise
endpoints reject group rows), `WsHub` group fan-outs (`group-message`,
`group-message-read` naming the `reader`, `group-reaction`, `group-deleted`,
`typing` with `groupId`), send-path un-archive for every member but the
author, core DTOs/events + parser branches, app group surface (ApiClient
group methods, repository state + events + send/read, ChatCache
`applyGroupRead` reader convergence, `GroupsScreen`/`GroupChatScreen`/
`GroupDetailScreen`/create dialog via `GroupsUi.kt`, contacts menu entry,
notification routing with the `is_group` intent flag for content/quick-reply/
mark-read), oracle `scripts/verify-groups.mjs`, protocol.md group contract,
ADR-0012 implementation note, screenshots `20-groups`/`21-group-chat` + dark
variants. Group media/voice in chat and group calls are deliberately out of
scope (ADR-0012 constraints) — group calls move to Phase 20.

---

### Phase 20 — Group calls (confbridge)

**Effort:** Large
**Status:** Planned
**Depends on:** Phase 19 (groups), ADR-0012 constraint 6 (PBX stays pairwise
until a call-phase decision)

Group voice calls over an Asterisk **confbridge** bridge (needs its own ADR,
planned as ADR-0014):

- The API creates a conference per group call and tears it down when the
  last participant leaves; SIP REGISTER/INVITE flows stay unchanged.
- The app's call engine dials the conference instead of the peer's SIP URI;
  pairwise calls are unaffected.
- Busy/ringing semantics for N participants, and which member may start a
  call (admin? any member?) need the ADR's decisions before implementation.

---

## Deferred indefinitely

| Feature | Reason |
|---------|--------|
| **End-to-end encryption** | Signal Protocol changes client, server and protocol fundamentally. Would need its own ADR before any work; revisit only when there is demand. |
| **Push notifications (FCM)** | Requires Google Play Services, conflicting with the self-hosted rule; would need its own ADR. Background delivery remains documented as a limitation. |

---

## Architecture decisions

| ADR | Area | Status |
|-----|------|--------|
| [ADR-0008](adr/0008-database-postgresql.md) | PostgreSQL 18 migration | Accepted (supersedes SQLite from ADR-0003/0007) |
| [ADR-0009](adr/0009-media-storage-s3.md) | Media storage — Garage default, S3-compatible interface | Accepted |
| [ADR-0010](adr/0010-message-delivery-states.md) | Read receipts (always-on) | Accepted |
| [ADR-0011](adr/0011-message-extras.md) | Reactions, replies, deletion semantics | Accepted (planned) |
| [ADR-0012](adr/0012-group-chat-scope.md) | Group chats — scope and one-to-one compatibility constraints | Accepted; shipped as Phase 19 (group calls deferred to Phase 20) |

---

## Open decisions

Remaining small choices, resolved during the phase that needs them:

1. **pgweb binding details** — decided in Phase 3: a separate developer
   overlay (`server/compose.dev.yaml`), no compose profile, loopback only.
2. **HikariCP sizing** — resolved in Phase 3: Spring Boot's defaults (pool
   max 10) are enough for the prototype; tune `spring.datasource.hikari.*`
   and document it in `docs/operations.md` once real load is measured.
3. **Video call server-side settings** — resolved while planning Phase 12:
   VP8 only, Asterisk stays in the media path (`direct_media=no`, no ICE on
   Asterisk), the RTP range is unchanged, and the codec becomes an admin
   setting (`vp8` or `none`). See [Phase 12](#phase-12--video-calls).
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