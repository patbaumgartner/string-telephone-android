# Changelog

All notable changes are documented here. The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No public release has been published yet.

### Changed

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

### Added

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
