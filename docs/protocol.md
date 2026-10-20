# Companion API protocol

This is the network boundary between the GPL Android app and AGPL companion
server. See [ADR-0003](adr/0003-messaging-companion-api.md),
[ADR-0004](adr/0004-licensing-gpl-agpl.md) and the
[Spring migration decision](adr/0007-managed-spring-admin.md).
The authoritative implementation is under `server/api/src/main/kotlin/`.

## Transport and authentication

Android uses an HTTPS base URL (HTTP is accepted only by explicit debug
installations). All Android routes below require a user API token in the
`Authorization` header with the `Bearer` scheme. Never send credentials in
query strings. API tokens are generated at provisioning time, shown once,
stored as SHA-256 hashes on the server and encrypted on the phone.

The server derives identity and message authorship from the authenticated
principal. Client-provided sender identity is ignored. Revoked/unknown user
tokens return `401`; admin session cookies cannot authenticate Android routes.

Administration uses a separate Spring Security session login, not user bearer
tokens. The old `API_ADMIN_TOKEN` is removed.

## Conventions

JSON bodies retain the Android v1 shapes. Unknown DTO fields are ignored.
Timestamps are epoch milliseconds. Errors are `{"error":"<reason>"}`:
`400` validation, `401` unauthorized, `404` missing user, `409` conflict,
`413` invalid message size, `503` PBX operation not confirmed.

## Health

`GET /health` is unauthenticated liveness:

```json
{"status":"ok"}
```

Liveness does not imply that PBX reconciliation or phone reachability works;
inspect the admin dashboard for PBX readiness.

`GET /ready` is unauthenticated readiness and probes PostgreSQL:

```json
{"status":"ok","database":"up"}
```

When the database is unreachable it answers `503` with `"database":"down"`.
Gate deployments and the Compose `api` healthcheck on `/ready`, never on
`/health` alone, now that PostgreSQL holds the state. `GET /actuator/health`
exposes the same probe (plus `info`); those are the only published Actuator
endpoints.

## User API (`/v1`)

### `GET /v1/me`

```json
{"username":"alice","displayName":"Alice"}
```

### `GET /v1/contacts`

All other API-active identities with derived presence:

- `"online"`: the user has an open realtime stream or a registered SIP
  contact on Asterisk.
- `"offline"`: neither, as confirmed by the PBX.
- `"unknown"`: no realtime stream and the PBX could not be queried.

`presenceUpdatedAtEpochMs` is when that state was observed (null when
unknown). PBX state is cached for about five seconds.

```json
{
  "contacts": [{
    "username":"bob",
    "displayName":"Bob",
    "presence":"online",
    "presenceUpdatedAtEpochMs":1760000000000
  }]
}
```

### `POST /v1/conversations/{peer}/messages`

```json
{"recipient":"bob","content":"hello","clientKey":"random-per-logical-message"}
```

- `recipient` must match `{peer}` (`400`).
- Content must be nonempty and at most 8192 UTF-8 bytes (`413`).
- The peer must exist and be active (`404`); self-messaging returns `400`.
- Extra sender fields never change authorship.
- Idempotency is per `(sender, recipient, clientKey)`. Retrying the same
  logical message returns its original record, not a duplicate.

Response `201`:

```json
{
  "id":"uuid",
  "sender":"alice",
  "recipient":"bob",
  "content":"hello",
  "createdAt":1760000000000,
  "clientKey":"random-per-logical-message"
}
```

### `GET /v1/conversations/{peer}/messages`

Returns conversation history in both directions, newest first.
`limit` defaults to 50 and is clamped to 1-200. Optional `cursor` is the
previous response's opaque `nextCursor`; invalid cursors return `400`.

```json
{
  "messages": [{
    "id":"uuid",
    "sender":"alice",
    "recipient":"bob",
    "content":"hello",
    "createdAt":1760000000000,
    "clientKey":"random-per-logical-message"
  }],
  "nextCursor":"MTc2MDAwMDAwMDAwMDp1dWlk"
}
```

The cursor is URL-safe Base64 of `"<createdAtEpochMs>:<messageId>"`.
Pagination uses both timestamp and message ID so records sharing a timestamp
are not skipped. `nextCursor` is null for an empty page; otherwise it identifies
the last record. Clients deduplicate by message ID.

## Realtime stream (`/v1/ws`)

Upgrade `GET /v1/ws` over WSS with the active user token in the
`Authorization` header using the `Bearer` scheme. Query tokens are rejected.
Invalid credentials close with `1008` immediately after upgrade.

- Multiple sessions per user receive events; a second connection does not
  disconnect the first.
- Server text frames retain the Android envelope:
  `{"type":"new-message","payload":"<JSON string>"}`. Decode `payload` as
  a message record containing `id`, `sender`, `recipient`, `content`,
  `createdAt`, `clientKey`.
- Events go to sender and recipient. They accelerate updates but do not
  replace durable cursor synchronization after reconnection.
- `{"type":"presence","payload":"<JSON string>"}` goes to every other
  connected user when a user's derived presence changes: first/last stream
  opened or closed, or a SIP registration change seen by the periodic PBX
  check (about every 10 seconds). The payload is
  `{"username":"bob","presence":"online","updatedAt":1760000000000}`.
  Clients ignore event types they do not know.
- Client frames do not create messages. Use the HTTP send endpoint.
- Disable/reset closes all user streams with `1008`.

## Admin API (`/v1/admin`)

`/admin/` serves Thymeleaf account/PBX administration. Keep `/admin/**` and
`/v1/admin/**` on a private HTTPS router.

1. `GET /admin/login` establishes a session and renders `_csrf`.
2. `POST /admin/login` submits form fields `username`, `password`, `_csrf`.
   Credentials must match `ADMIN_USERNAME` and `ADMIN_PASSWORD`. Success
   redirects to `/admin/`; failure to `/admin/login?error`.
3. Keep the session cookie, read the new CSRF token from the authenticated
   page and send `X-CSRF-TOKEN` on every admin API POST.
4. `POST /admin/logout` invalidates the session (also requires CSRF).

Unauthenticated admin requests redirect to login. Missing/invalid CSRF tokens
return `403`. Responses are no-store; pages apply CSP and frame denial.

| Route | Body | Effect |
| --- | --- | --- |
| `POST /v1/admin/create-user` | `{"username":"bob","displayName":"Bob","sipPassword":"<optional passphrase>"}` | Creates and provisions API/PBX identity. `201` when ready, `202` when saved but PBX pending. Returns `username`, `displayName`, `apiToken`, `sipSecret`, `ready`. Save both secrets once even if pending. |
| `POST /v1/admin/reset-user` | `{"username":"bob","sipPassword":"<optional passphrase>"}` | Replaces both credentials and ends calls. Returns the same credential shape with readiness. Old API token stops working immediately. |
| `POST /v1/admin/revoke-user` | `{"username":"bob"}` | Blocks API/SIP, closes streams and ends calls. `503` means PBX change remains pending; API is already blocked. |
| `POST /v1/admin/set-enabled` | `{"username":"bob","enabled":true}` | Enables/disables and reconciles PBX. |
| `GET /v1/admin/list-users` | none | Lists `username`, `displayName`, desired `enabled`, confirmed `active`. No secrets. |
| `GET /v1/admin/pbx` | none | Desired settings, sync state, endpoints and active channels. Unreachable PBX returns `503`. |
| `POST /v1/admin/pbx/settings` | `{"codecs":"opus,ulaw","ringSeconds":30,"maxContacts":2,"externalAddress":"","localNetwork":"","symmetricMedia":true}` | Validates, persists, applies and verifies settings. |
| `POST /v1/admin/pbx/reconcile` | `{}` | Retries configuration and pending disconnections. |

Usernames match `[a-z][a-z0-9-]{0,31}`, excluding dialplan special extensions
`s`, `h`, `i`, `t`. Duplicate names return `409`. SIP passphrases accept
24-1024 characters, including hyphens, without truncation; omitted/empty
values generate a random secret. API tokens always remain generated.

The same lifecycle is available through `stringtelephone-provision`, which
authenticates to the running server. See [operations](operations.md).

## Not included

No end-to-end message encryption, remote message deletion, PSTN/trunk
management, or push delivery to a phone whose app process is not running. Local app clearing does not delete
server history. See [operations](operations.md) for failure recovery.
