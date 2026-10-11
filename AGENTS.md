# String Telephone — Agent Guide

Self-hosted one-to-one calls and messages: Android app (GPL-3.0), Kotlin/Ktor
companion API (AGPL-3.0), Asterisk 22 PBX. Start with [README.md](README.md)
and [docs/adr/](docs/adr/) for design decisions; this file covers what is easy
to get wrong. If a convention changes, correct this file in the same commit.

## Build and validate

Gradle needs the SDK location from `ANDROID_HOME` or `sdk.dir` in
`local.properties`. Builds on JDK 17, same as CI.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew --no-daemon :server:api:test
yamllint .
node scripts/check-adrs.mjs
node scripts/check-secrets.mjs
node scripts/check-design.mjs
```

`check-design.mjs` is a local gate only (requires the design reference project
next to this one; not run in CI). The full CI gate is everything above it.

Start with [README.md](README.md), [docs/protocol.md](docs/protocol.md),
[docs/operations.md](docs/operations.md), and [docs/adr/](docs/adr/) for
design decisions; this file covers what is easy to get wrong. If a convention
changes, correct this file in the same commit.

## Build traps

- **Malformed XML fails at merge/compile, not at authoring time.** The launcher
  icon, the manifest, and every drawable are parsed by different tools at
  different stages; run `assembleDebug`, not just `compileDebugKotlin`, before
  claiming a build is green.
- `settings.gradle.kts` includes `:app` and `:server:api` only when their
  directories exist. Do not replace that with unconditional `include()` — the
  server-only Docker build copies only `core/` and `server/api/`.
- `server/api` produces two start scripts: the standard `api` plus
  `stringtelephone-provision` via `provisioningStartScripts`. The extra script
  is copied into `build/install/api/bin` by `installDist`; pointing its
  `outputDir` at the install directory directly breaks `installDist` ("neither
  empty nor an installation").
- Release R8 needs `-dontwarn com.google.errorprone.annotations.**` (Tink's
  compile-only annotations) and the linphone keep rules in
  `app/proguard-rules.pro`. Missing-class errors there mean someone changed a
  dependency's annotation surface.
- The Gradle configuration cache is on; avoid reading files at configuration
  time.

## Architecture

- Manual DI: one `AppContainer` built in `StringTelephoneApp.onCreate()`,
  reached via `(application as StringTelephoneApp).container` or the
  `Context.stringTelephoneContainer` extension. No Hilt, no Koin.
- One `AppViewModel` owns navigation as a sealed `AppScreen` state flow.
  Screens are functions of (state, viewModel); no Navigation-Compose.
- `CallEngine` (app/call) is the only seam to liblinphone. The SDK must not
  leak into UI or domain code; GPL obligations flow from this link (ADR-0002).
- Messaging goes through the companion API with client idempotency keys and
  cursor sync; `ChatCache` is framework-free and unit-tested (ADR-0003).
- The server derives message authorship from the authenticated principal and
  ignores client-provided sender identity.

## Licensing invariants

- `app/` links linphone-sdk (GPL-3.0), so app code stays GPL-compatible. No
  proprietary dependency may enter the app module (ADR-0004).
- `server/api` is AGPL-3.0 and must not link GPL Android components; it talks
  to the app only over the documented network API.
- `scripts/test-prototype.sh` credentials (`prototest-*`) are placeholders for
  throwaway containers, never production secrets.

## Verification layers

1. Unit tests: `:core:test`, `:app:testDebugUnitTest`, `:server:api:test`.
   The `ScreenshotTest` suite renders every screen to
   `app/build/screenshots/` — refresh `docs/screenshots/` in the same commit
   whenever UI output changes.
2. Static gates: `node scripts/check-adrs.mjs`,
   `node scripts/check-secrets.mjs`, and locally
   `node scripts/check-design.mjs`.
3. Live prototype: `scripts/test-prototype.sh` (Docker) proves REGISTER,
   INVITE/BYE, and the negative auth path against real Asterisk config.
4. CI runs 1 + assemble + lint on every push.
