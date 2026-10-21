# Architecture

String Telephone consists of an Android application, a framework-free Kotlin
core, a companion API, and an Asterisk PBX. The Android app uses manual
dependency injection and a single-ViewModel navigation state; module
boundaries reflect calling and messaging.

## Modules and dependency direction

```text
app (Android, GPL) -------> core (Kotlin models and protocol helpers)
server/api (Spring Boot, AGPL) --> PostgreSQL 18 (ADR-0008)

app -- REST + WebSocket --> server/api --> PostgreSQL 18
app -- SIP + media -----> Asterisk
```

The server never links Android or liblinphone code. Network contracts, not
in-process dependencies, connect the app to the deployed services. See
[ADR-0004](adr/0004-licensing-gpl-agpl.md) for licensing boundaries.

The planned feature roadmap (Garage-backed media storage, read
receipts, image and voice messaging, reactions/replies/deletion, notification
quick reply, previews/search, editing, chat management) lives in
[feature-plan.md](feature-plan.md) with its decision records
[ADR-0008](adr/0008-database-postgresql.md)–[ADR-0012](adr/0012-group-chat-scope.md).
Group chats stay out of scope, but ADR-0012 records the compatibility
constraints the one-to-one designs must keep.

## Android components

Sources live under `app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/`.

- `StringTelephoneApp` creates one process-wide `di/AppContainer` and starts
  `session/SessionController`, which owns everything that must outlive the
  UI: sign-in retries, SIP registration, the realtime stream, and incoming
  call, missed call and message notifications. `session/KeepAliveService` is
  the `specialUse` foreground service that keeps the process alive while
  signed in.
- `ui/AppViewModel` owns navigation through the sealed `AppScreen` state and
  coordinates user actions. Compose screens consume state and delegate actions;
  there is no navigation or dependency-injection framework.
- `data/StringTelephoneRepository` coordinates account loading, contacts,
  presence, unread counts, messaging, and realtime events. It exposes
  observable state to the UI and the session controller.
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
  notifications; `notify/CallActionReceiver` handles Decline from the
  incoming-call notification.
- `security/SecureCredentialStore` implements the credential boundary using
  encrypted preferences backed by Android Keystore. Backups are disabled.

The `core/` module contains shared DTOs, domain models, URL and transport
helpers, SIP digest utilities, and protocol-level tests. It has no Android
dependency.

## Companion API and PBX

Sources live under `server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/`.

- `Main` wires Spring Boot, database, users, messages and the WebSocket handler.
  Integration tests start the real embedded HTTP server on random ports.
- `Api` authenticates Android bearer-token requests; `Admin` exposes
  server-rendered Thymeleaf pages and session-protected administration APIs.
- `Security` isolates stateless Android routes from CSRF-protected admin
  sessions; admin credentials are mandatory environment configuration.
- `AccountService` persists desired PBX state, manages lifecycle changes,
  coordinates disconnection, and retries failed reconciliation.
- `AsteriskGateway` atomically writes managed configuration, reloads and
  verifies endpoints over private AMI, reads status and hangs up channels.
- `Users` manages provisioned identities and token lookup.
- `Messages` handles persistence, idempotent sends, cursor-based sync and the
  read marks; `WsHub` fans new-message and read-receipt events out to the
  connected senders and recipients, and `WsSessions` keeps the live session
  registry that both the hub and the presence service read.
- `Media`/`MediaApi` accept authenticated multipart uploads for a conversation
  peer, generate server-side image thumbnails and dimensions, and stream
  originals and thumbnails back to conversation participants only; the pluggable
  `MediaStore` seam lives in `media/` ([ADR-0009](adr/0009-media-storage-s3.md)).
- `Database` access is PostgreSQL 18 through a Spring Boot auto-configured
  HikariCP `DataSource` and `JdbcClient`; multi-statement writes use narrowly
  scoped transactions and Flyway applies `db/migration` at startup
  ([ADR-0008](adr/0008-database-postgresql.md)).
  `ProvisioningCli` provides `stringtelephone-provision`, which authenticates
  to the running API rather than writing the database independently.
- `ServerTls` configures the optional TLS listener.

`server/asterisk/` owns the Asterisk image, PJSIP configuration, dialplan, and
container entrypoint. `server/compose.yaml` assembles the prototype stack.
The API and PBX share the `pbx-config` volume. Only the API writes desired
account/configuration changes; AMI is reachable privately and never published.
There are no hard-coded accounts. See [ADR-0007](adr/0007-managed-spring-admin.md)
and [ADR-0008](adr/0008-database-postgresql.md).
The app derives its SIP registrar from the configured companion host; that host
must also route to the PBX.

## Message and call flows

```text
Compose -> AppViewModel -> repository -> REST send -> Messages -> PostgreSQL
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

A message becomes read only while the conversation is on screen: the chat
screen asks the repository to mark the rendered messages read, and the
repository posts them to `POST /v1/conversations/{peer}/read`. Notifications,
background sync and cursor catch-up never mark anything read, because a receipt
means a human looked at the message. The server keeps the read state in the
message row, so both participants see it in the history, and the author learns
about it through the `message-read` event
([ADR-0010](adr/0010-message-delivery-states.md)).

Incoming calls require a live app process. While signed in, the keep-alive
service holds it; after reboot or force-stop, nothing rings until the app is
reopened. See [ADR-0006](adr/0006-background-call-policy.md).
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
