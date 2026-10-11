# Changelog

All notable changes are documented here. The project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No public release has been published yet.

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
- Foreground call service with CallStyle notification during calls
- Encrypted credential storage (Android Keystore via security-crypto)
- Pure-Kotlin `core` module with SIP digest helpers, server URL validation,
  cursor codecs, presence logic, and message limits, all unit-tested
- Companion API (Kotlin/Ktor + SQLite): health, users, messages, admin
  routes; integration-tested
- Provisioning CLI writing PJSIP endpoint fragments
- Asterisk 22 PJSIP prototype config and container image
- SIP prototype scripts: digest registration, two-party call, negative
  auth path (`scripts/test-prototype.sh`)
- CI workflow running tests, lint, and both APK variants
- ADRs for PBX choice, SIP engine, messaging, licensing, SDK levels, and
  background call policy
- Documentation: companion API protocol reference (`docs/protocol.md`,
  promised by ADR-0003) and operator guide (`docs/operations.md`)
