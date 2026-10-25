# String Telephone — Agent Guide

Self-hosted one-to-one calls and messages: Android app (GPL-3.0), Kotlin/Spring Boot
companion API (AGPL-3.0), Asterisk 22 PBX. Start with [README.md](README.md)
and [docs/adr/](docs/adr/) for design decisions; this file covers what is easy
to get wrong. If a convention changes, correct this file in the same commit.

## Build and validate

Gradle needs the SDK location from `ANDROID_HOME` or `sdk.dir` in
`local.properties`. The build runs on JDK 27 (bytecode target stays 17);
CI builds on JDK 27 too. `:server:api:test` needs a running Docker daemon
(Testcontainers provisions PostgreSQL per test JVM; GitHub-hosted runners
provide Docker already).

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease :server:api:test :server:api:installDist
yamllint .
find . -name '*.sh' -not -path './*/build/*' -exec shellcheck {} +
node scripts/check-adrs.mjs
node scripts/check-secrets.mjs
node --test scripts/test-deploy.mjs
node --test scripts/test-provisioning.mjs
node scripts/check-design.mjs
```

`check-design.mjs` is a local gate only (requires the design reference project
next to this one; not run in CI). The full CI gate is everything above it.

See [docs/architecture.md](docs/architecture.md) for the module map,
[docs/protocol.md](docs/protocol.md) for contracts, and
[docs/operations.md](docs/operations.md) for deployment.
[docs/setup.md](docs/setup.md) is the beginner app/server setup path.

Compose binds the host's plain HTTP API port to `127.0.0.1` by default. Set
`ST_API_HTTP_BIND=0.0.0.0` only for a trusted private-network development
device or emulator; never expose that HTTP listener to the Internet.

Onboarding takes separate API-token and SIP-password inputs; administration
automatically installs SIP digests and dialplan entries through managed files
and private AMI reload/verification. Only verified API
identity opens Contacts and starts SIP. Keep launcher/notification resources
and all Material colour roles in sync with the cyan theme.
Outgoing calls and incoming answers must pass the microphone permission gate.
The call service uses only the microphone FGS type: there is no registered
Telecom ConnectionService or dialer role to authorize a phoneCall FGS.
Background side effects (SIP start, sign-in retry, call/message
notifications) belong in `session/SessionController`, never in
`AppViewModel`, which dies with the Activity. `KeepAliveService` (specialUse
FGS) runs while signed in. Start foreground services only from a foreground
context, and call `startForeground` before any early `stopSelf`.
The microphone FGS starts from VM call actions while visible. Notification
Answer opens `MainActivity` so the microphone gate still applies.

`scripts/deploy-device.sh` and `scripts/deploy-emulator.sh` install and launch
on one ready target. Use `DEVICE_SERIAL` or `EMULATOR_SERIAL` to disambiguate.
The emulator helper requires an already running, fully booted AVD and does
not manage emulator processes. Mocked helper tests require Node.js 24.

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
  Its `classpath` must include `runtimeClasspath`, not just the API JAR.
  Run `node --test scripts/test-provisioning.mjs` after `installDist` to prove
  that creating, listing and revoking accounts works from the distribution.
- Release R8 needs `-dontwarn com.google.errorprone.annotations.**` (Tink's
  compile-only annotations) and the linphone keep rules in
  `app/proguard-rules.pro`. Missing-class errors there mean someone changed a
  dependency's annotation surface.
- The Gradle configuration cache is on; avoid reading files at configuration
  time.
- The API runtime image (`eclipse-temurin:27-jre`) is minimal and ships no HTTP
  client; the Compose healthcheck probes `/ready` with `curl`. The Dockerfile
  installs curl — removing it makes every container permanently unhealthy and
  `docker compose up --wait` fails.
- Admin uses Spring Security sessions, Thymeleaf POST forms and CSRF.
  `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `PBX_AMI_PASSWORD` and `DB_PASSWORD` are
  mandatory. Keep Android bearer/WebSocket routes stateless and wire-compatible.
  Never expose AMI or mount the Docker socket. The provisioning CLI calls the
  running API; it must not write the database directly. Failed PBX changes stay
  visibly pending and are retried. Disable/reset must disconnect API,
  WebSockets and PBX calls, not just mark a user inactive.

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
- Read receipts (ADR-0010): the server stores a read state per message and
  announces it with the `message-read` event; the app's `SendState` is
  `PENDING / SENT / READ / FAILED`. Only the foreground chat screen may mark
  a conversation read — never a notification handler, a background sync or a
  cursor catch-up. A receipt means a human looked at the message.
- The server derives message authorship from the authenticated principal and
  ignores client-provided sender identity.
- Server persistence is PostgreSQL 18 (ADR-0008): Spring Boot auto-configures
  the HikariCP `DataSource` (`spring.datasource.*` from `DB_*` env vars) and
  the `JdbcClient` bean; services bind every value with `param()`/`params()`,
  never concatenate SQL. Multi-statement writes run inside narrowly scoped
  `TransactionTemplate` transactions — never across PBX or HTTP calls. Schema
  changes ship as forward-only Flyway migrations in
  `server/api/src/main/resources/db/migration`; no ORM, no `DriverManager`,
  no hand-rolled commit/rollback.

## Licensing invariants

- `app/` links linphone-sdk (GPL-3.0), so app code stays GPL-compatible. No
  proprietary dependency may enter the app module (ADR-0004).
- `server/api` is AGPL-3.0 and must not link GPL Android components; it talks
  to the app only over the documented network API.
- `scripts/test-prototype.sh` credentials (`prototest-*`) are placeholders for
  throwaway containers, never production secrets.

## Verification layers

1. Unit tests: `:core:test`, `:app:testDebugUnitTest`, `:server:api:test`
   (Docker required — Testcontainers starts a PostgreSQL 18 container that
   gives each test its own database). The `ScreenshotTest` suite renders every
   screen to
   `app/build/screenshots/` — refresh `docs/screenshots/` in the same commit
   whenever UI output changes.
2. Static gates: `node scripts/check-adrs.mjs`,
   `node scripts/check-secrets.mjs`, and locally
   `node scripts/check-design.mjs`, plus YAML lint, ShellCheck, and
   `node --test scripts/test-deploy.mjs`. Feature oracles
   `scripts/verify-scrollbar.mjs`, `verify-plan-status.mjs`,
   `verify-postgres.mjs`, `verify-audio-route.mjs`,
   `verify-call-ui-routes.mjs`, `verify-read-receipts.mjs`,
   `verify-typing.mjs` and `verify-screenshots.mjs` run from the gate
   ledgers; the last four are
   also appended to `scripts/run-session-verify.sh`
   (`verify-screenshots.mjs` needs the suite's `app/build/screenshots`).
3. Live prototype: `scripts/test-prototype.sh` (Docker) proves REGISTER,
   INVITE/BYE, and the negative auth path against real Asterisk config.
   It also proves read receipts end to end (send, author-may-not-mark,
   recipient mark, history `readAt`, idempotence) and revocation of active
   calls and password resets. It uses a unique `st-admin-test-*` project and
   deletes only its own volumes; stop a demo first to free SIP/RTP ports.
   It starts only the services it exercises (Asterisk, PostgreSQL, API) and
   does not verify Android audio or the media store.
4. CI runs 1 + assemble + lint + API installDist on main pushes and pull requests.
   Security CI builds app and API for CodeQL and reviews PR dependency changes.
   Dependabot patch/minor auto-merge requires repository auto-merge and branch
   protection settings; see CONTRIBUTING.md. Never check out PR code in its
   write-token `pull_request_target` workflow.
