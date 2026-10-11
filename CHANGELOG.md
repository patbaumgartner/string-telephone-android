# Changelog

All notable changes are documented here. The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No public release has been published yet.

### Changed

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
