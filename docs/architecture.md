# Architecture

String Telephone consists of an Android application, a framework-free Kotlin
core, a companion API, and an Asterisk PBX. The Android app uses the same
manual dependency injection and single-ViewModel approach as Room Watchdog,
but the module boundaries reflect calling and messaging rather than sensors.

## Modules and dependency direction

```text
app (Android, GPL) -------> core (Kotlin models and protocol helpers)
server/api (Ktor, AGPL) --> core

app -- REST + WebSocket --> server/api --> SQLite
app -- SIP + media -----> Asterisk
```

The server never links Android or liblinphone code. Network contracts, not
in-process dependencies, connect the app to the deployed services. See
[ADR-0004](adr/0004-licensing-gpl-agpl.md) for licensing boundaries.

## Android components

Sources live under `app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/`.

- `StringTelephoneApp` creates one process-wide `di/AppContainer`.
- `ui/AppViewModel` owns navigation through the sealed `AppScreen` state and
  coordinates user actions. Compose screens consume state and delegate actions;
  there is no navigation or dependency-injection framework.
- `data/StringTelephoneRepository` coordinates account loading, contacts,
  messaging, and realtime events. It exposes observable state to the UI.
- `network/CompanionApiClient` handles authenticated REST requests.
  `RealtimeClient` implements the `RealtimeStream` boundary for WebSocket events.
- `chat/ChatCache` merges optimistic sends, server acknowledgements, and cursor
  sync results using message IDs and client idempotency keys. The cache is
  in-memory, framework-free, and unit-tested; it is not durable offline storage.
- `call/CallEngine` isolates liblinphone. Its implementation handles SIP
  registration, call state, and media; SDK types must not leak into the UI or
  shared core. `CallForegroundService` provides the ongoing-call foreground
  service.
- `notify/AppNotifications` owns notification channels and call/message
  notifications.
- `security/SecureCredentialStore` implements the credential boundary using
  encrypted preferences backed by Android Keystore. Backups are disabled.

The `core/` module contains shared DTOs, domain models, URL and transport
helpers, SIP digest utilities, and protocol-level tests. It has no Android
dependency.

## Companion API and PBX

Sources live under `server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/`.

- `Main` wires the Ktor application, database, users, messages, and WebSocket hub.
  The same `stModule` routes are used by integration tests.
- `Api` authenticates user requests; `Admin` exposes token-protected provisioning.
- `Users` manages provisioned identities and token lookup.
- `Messages` handles persistence, idempotent sends, and cursor-based sync.
- `WsHub` fans new-message events out to connected senders and recipients.
- `Database` owns SQLite storage. `ProvisioningCli` provides the separate
  `stringtelephone-provision` start script installed alongside the API.
- `ServerTls` configures the optional TLS listener.

`server/asterisk/` owns the Asterisk image, PJSIP configuration, dialplan, and
container entrypoint. `server/compose.yaml` assembles the prototype stack.
The app derives its SIP registrar from the configured companion host; that host
must also route to the PBX.

## Message and call flows

```text
Compose -> AppViewModel -> repository -> REST send -> Messages -> SQLite
                              |                         |
                              v                         v
                          ChatCache <--- WebSocket <-- WsHub
                              ^
                              |
                          REST cursor sync

Compose -> AppViewModel -> CallEngine -> liblinphone -> Asterisk -> peer
                              |
                              +-> call state -> UI / foreground service
```

The API derives authorship from the authenticated principal, not a sender field
provided by the client. WebSocket events accelerate updates; persisted messages
and cursor sync provide catch-up. Messages are not end-to-end encrypted.

Incoming calls require a live app process; force-stop prevents ringing until
the app is reopened. See [ADR-0006](adr/0006-background-call-policy.md).
Transport configuration and deployment limitations belong in
[operations.md](operations.md), while endpoint contracts live in
[protocol.md](protocol.md).

## Verification and repository tooling

- JVM tests cover `core/`, Android logic and Robolectric screenshots, and the API.
- Android lint and debug/release assembly exercise manifests, resources, and R8.
- `scripts/test-prototype.sh` checks live SIP behaviour against Docker/Asterisk.
- ADR and secret checks, YAML lint, ShellCheck, and mocked deployment-helper
  tests run in CI. The design-reference check remains local.
- Security CI analyzes Android, core, and server Kotlin with CodeQL and reviews
  dependency changes. Dependabot covers Gradle, Actions, and server Dockerfiles.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for commands, deployment helpers,
and the repository settings required before enabling dependency auto-merge.
