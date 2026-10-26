# Changelog

All notable changes are documented here. The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No public release has been published yet.

### Changed

- The chat now supports voice messages (Phase 11). Hold the mic button to
  record, slide up > 140 px to lock for hands-free recording, or cancel by
  sliding left. The bubble renders a waveform, duration and playback controls
  (play/pause, speed cycling through five presets, tap-to-scrub). Audio is
  recorded as Opus (API 29+) or AAC (API 26–28) through the Phase 4 media
  pipeline. The waveform is persisted on the message as comma-separated
  amplitudes, clamped to 0–100 server-side and capped at 64 samples.
- The chat now supports message deletion (Phase 10, ADR-0011). Long-press a
  bubble to choose Delete for me (local hide) or Delete for both (tombstone);
  deleted messages render as a "Message deleted" placeholder on both phones.
  The server clears the content, keeps the row so reply references stay
  stable, and fans a `message-deleted` frame; updates are optimistic and roll
  back if the request fails.
- The chat now supports reply/quote (Phase 9, ADR-0011). Long-press a bubble
  to choose Reply or React; replying shows a quoted banner above the composer
  and the sent message renders a snapshot of the quoted message (author and
  text or photo preview) above its content. The server stores a nullable
  `reply_to` on the message, validates that the target belongs to the same
  conversation, and carries `replyToId` in history pages and
  `new-message` frames.
- The chat now supports emoji reactions (Phase 8, ADR-0011). Long-press a
  bubble to open a quick-emoji picker; reaction chips with counts appear
  under the bubble and tapping a chip toggles the user's own reaction. The
  server stores one reaction per author per message (reacting again
  replaces it), fans changes out as `message-reaction` frames, and embeds
  reactions in history pages. Chip updates are optimistic and roll back if
  the request fails; messages not yet stored on the server cannot be
  reacted to.
- The chat now supports image sharing (Phase 6). An attachment button in the
  composer opens the system Photo Picker; the picked image is downscaled
  client-side (max 1920px long edge, JPEG 85) before upload, sent as an
  `IMAGE` message referencing the uploaded media, and rendered as an inline
  thumbnail in the bubble — tap it to open a fullscreen viewer. Failed image
  uploads behave exactly like failed text messages: visible, tap to resend.
- The chat now shows a "typing…" indicator in the contact header (Phase 7).
  Ephemeral WebSocket signals only; no REST, no persistence. The app debounces
  at 500 ms on send and expires the indicator after 5 s without a refresh.
  Sending a message clears the peer's indicator immediately.
- Messages now carry read receipts (ADR-0010). The server stores a per-message
  read state, exposes it as `readAt` in the conversation history and
  announces it as a `message-read` event on the realtime stream. The app
  marks a conversation read only while it is on screen — a notification, a
  background sync or a cursor catch-up never marks anything read — and shows
  a double tick in the brand colour once the peer has read a message.
  Read receipts are always on and mutual; there is no opt-out.
- The API's Compose HTTP port now binds to loopback by default; direct HTTP
  access from a development phone or emulator requires an explicit private-LAN
  bind. Android REST requests also have bounded write and total-call times,
  and message pagination now uses the complete timestamp/ID cursor so messages
  with matching timestamps are not skipped.
- The chat now looks like a familiar messenger: sent and received bubbles
  with tails on the first message of each run, time and status (clock while
  sending, tick when stored, warning when it failed) inside the bubble, day
  separators, a patterned background, a rounded message field with a round
  send button, and a call button with presence in the chat header. Tapping a
  failed message resends it with its original client key, so the server never
  stores it twice. Messages written while no server is reachable now stay
  visible as failed instead of disappearing.
- Incoming calls now open the call screen directly on a locked or dark screen,
  like the phone app. Contacts warns when Android's full-screen notification
  permission or notifications are off and opens the matching setting.
- Fixed calls not ringing and messages not notifying while the app was
  closed. A minimal "Connected" foreground service now keeps SIP and the
  realtime stream alive while signed in. Incoming calls show an Answer/Decline
  call notification (full-screen when permitted) and leave a missed-call
  notification. Messages produce per-conversation notifications and unread
  badges in Contacts.
- Contacts now show live online/offline presence. The API derives it from
  SIP registrations and open realtime streams, and pushes `presence`
  WebSocket events.
- Failed outgoing calls no longer drop silently back to Contacts. The call
  screen now says why the call failed (busy, declined, not reachable, no
  answer, not connected) and closes after a few seconds or when you tap
  Close. A late hang-up event from an earlier call no longer ends a new call,
  and a second incoming call during a call is rejected as busy.
- Settings shows the app version and the git commit it was built from. The
  commit comes from `GIT_COMMIT` when set, or `git rev-parse` otherwise.
- The launcher icon's speech bubble is now white on cyan.
- Migrated the Kotlin companion API to Spring Boot and Thymeleaf, preserving
  Android REST/WebSocket contracts; replaced the token-paste admin page with
  session login, CSRF-protected forms and mandatory environment-configured
  `ADMIN_USERNAME` / `ADMIN_PASSWORD` with long-passphrase support
- Administration now provisions and reloads Asterisk automatically, manages
  account enable/disable and credential resets, terminates affected calls,
  reports PBX status, validates calling/network settings and retries pending
  configuration; the installed CLI uses the running service
- Calls now request microphone permission before dialing/answering, explain
  denial, and use the microphone foreground-service type without requiring an
  unimplemented self-managed Telecom account
- Fixed the installed provisioning CLI's missing runtime classpath and
  zero-argument `list-users` command; CI now exercises the installed account
  lifecycle against an isolated HTTP fixture
- Unified cyan branding across launcher, notification accents and Material
  light/dark surfaces; redrew the launcher handset glyph and added a
  dedicated themed (monochrome) icon layer; improved screen spacing, call-control selection,
  keyboard handling, empty/error states and readable sign-in guidance
- Added regression coverage for long, hyphenated credentials in Android
  sign-in, SIP configuration, server authentication and the web admin page;
  documented passphrase support and the separate generated API token
- Sign-in now verifies the API token and username before opening Contacts or
  starting SIP, accepts the separate provisioned SIP password, and reports
  rejected credentials/network failures without leaving a misleading empty list
- Clearing local data signs out and removes local credentials as well as cache
- Added a beginner app/server setup guide, LAN audio configuration and explicit
  warnings about the prototype script's destructive cleanup
- Build toolchain upgraded to JDK 27 (CI on Temurin 27, API image built with
  `gradle:9.8.1-jdk27` on `eclipse-temurin:27-jre`); the Java bytecode target
  stays 17 for Android D8 compatibility (ADR-0005 amended)
- `docs/protocol.md` documents the implemented `/v1/ws` realtime stream
  (Authorization bearer auth, `1008` on bad token, `new-message` envelopes)
- The API's Compose healthcheck now probes `/ready`, which runs a PostgreSQL
  `SELECT 1` (via Spring Boot Actuator's data-source health indicator) instead
  of trusting a process-only `/health`; enabling a revoked account now sets
  `active` in the same statement, so a PBX outage can no longer leave the
  account deactivated. The companion API now stores users, messages, desired
  PBX identities and settings in PostgreSQL 18 instead of SQLite (ADR-0008). Access goes through
  Spring Boot's auto-configured HikariCP `DataSource` and `JdbcClient` with
  narrowly scoped transactions; the schema ships as forward-only Flyway
  migrations applied at startup. The compose stack gains a `db` service with a
  mandatory `DB_PASSWORD` (`API_DB` and the `api-data` volume are removed), an
  optional loopback-only pgweb developer overlay, and the server tests run on
  Testcontainers PostgreSQL locally and in CI. Fresh installations only — no
  SQLite import (ADR-0007/0008 semantics).

### Fixed

- Sending an image message and loading any conversation history failed with
  a 500: `Messages.insertMedia` never bound the `mediaId`/`width`/`height`
  parameters of its INSERT, and `Reactions.listForMessages` passed its id
  array as one parameter instead of spreading it across the `IN (?, …)`
  placeholders. Both are bound correctly now, and the PostgreSQL integration
  tests for images, voice and reactions pass.
- The API container never turned healthy: its Compose healthcheck probes the
  readiness endpoint with `curl`, but the slim JRE runtime image ships no HTTP
  client, so `docker compose up --wait` failed on a perfectly fine API. The
  image now installs curl.
- The media object store's Garage configuration could not start Garage and
  carried a credential in the repository. The configuration now uses the
  Garage 1.0 schema, the RPC secret and the administration token come from the
  environment, and the secret scan passes again.
- The Compose file had the media store nested under `networks:` instead of
  `services:`, so the whole stack failed validation. The prototype now starts
  only the services it exercises.
- The companion API could no longer start: a wiring change had dropped the
  presence service from the bean definitions, so the Spring context failed to
  resolve it. The realtime session registry is now a separate, dependency-free
  type (`WsSessions`), which keeps the presence and WebSocket dependencies
  acyclic and makes the wiring explicit again.

### Added

- Screenshot coverage for every message delivery state: the committed
  `docs/screenshots/10-chat-delivery-states.png` shows the clock, the single
  tick, the double tick of a read message and the error mark side by side.
- Calls can hand audio to a paired Bluetooth headset. The call screen shows
  one button per available route — earpiece, speaker, and Bluetooth only
  while a Bluetooth output exists — and the engine follows device-list
  changes: it takes over when a headset connects mid-call, falls back to the
  earpiece when it disconnects, honours an explicit button choice across a
  transient device-list flap, and mirrors the route liblinphone actually
  selected (`AudioRouteTest` covers the resolution rules).
- The chat shows a scroll-position thumb on the trailing edge while scrolling:
  it derives size and position from the live `LazyListLayoutInfo`, lingers
  briefly after scrolling stops, then fades, and never appears in short
  conversations. Geometry is a pure, unit-tested function
  (`ScrollbarMetricsTest`).
- Visual redesign: Manrope typeface (light/medium/regular/semibold, OFL) with
  a semantic Material 3 palette — cyan brand, porcelain/ink neutrals,
  coral/amber accents — with full light and dark variants, redesigned screens,
  and a complete adaptive launcher icon (background + foreground +
  monochrome), matching the project design reference
- Display names everywhere in the UI: contacts, chat headers, call cards, and
  settings show provisioned display names; usernames remain sign-in identity
  only
- Robolectric screenshot tests (`ScreenshotTest`) rendering every screen to
  PNG without a device; committed copies live in `docs/screenshots/`
- `scripts/check-design.mjs` gate pinning fonts and adaptive icon layers
- Android prototype: onboarding, contacts with presence, chat, and call
  screens backed by a single `AppViewModel`
- Foreground microphone service with an ongoing notification during calls
- Encrypted credential storage (Android Keystore via security-crypto)
- Pure-Kotlin `core` module with SIP digest helpers, server URL validation,
  cursor codecs, presence logic, and message limits, all unit-tested
- Companion API (Kotlin/Spring Boot + SQLite): health, users, messages, admin
  routes; integration-tested
- Provisioning CLI printing API token and SIP secret for account setup
- Asterisk 22 PJSIP prototype config and container image
- SIP prototype scripts: digest registration, two-party call, negative
  auth path (`scripts/test-prototype.sh`)
- CI workflow running tests, lint, and both APK variants
- ADRs for PBX choice, SIP engine, messaging, licensing, SDK levels, and
  background call policy
- Documentation: companion API protocol reference (`docs/protocol.md`,
  promised by ADR-0003) and operator guide (`docs/operations.md`)
