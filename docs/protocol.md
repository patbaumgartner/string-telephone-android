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
`400` validation, `401` unauthorized, `403` forbidden (e.g. media outside the
caller's conversation), `404` missing user or record, `409` conflict,
`413` invalid message or media size, `503` PBX operation not confirmed.

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
- Text messages: `content` must be nonempty and at most 8192 UTF-8 bytes
  (`413`); `type` defaults to `TEXT`.
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
  "clientKey":"random-per-logical-message",
  "type":"TEXT"
}
```

`readAt` is the epoch millisecond at which the recipient read the message. It
is omitted while the message is unread, so a message the caller just sent
never carries it.

#### Image messages

Send `{"type":"IMAGE","recipient":"bob","mediaId":"<id>","clientKey":"..."}`.
The `content` field stays empty for image messages.

- `mediaId` must reference media uploaded to `POST /v1/media` by the caller
  for this peer (`404` unknown media, `403` when the record belongs to
  another conversation, `400` when it is not `ready`, missing `mediaId` is
  `400`).
- The server derives the author from the authenticated principal and stores
  `width`/`height` from the upload record — clients cannot fake dimensions.

Image fields in responses: `type` (`"TEXT"` default, or `"IMAGE"`),
`mediaId` (null for text), `width`/`height` (decoded pixel dimensions of the
original image; `0`/absent when unknown). The same fields appear in history
responses and in the `new-message` event payload.

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
    "clientKey":"random-per-logical-message",
    "readAt":1760000005000
  }],
  "nextCursor":"MTc2MDAwMDAwMDAwMDp1dWlk"
}
```

The cursor is URL-safe Base64 of `"<createdAtEpochMs>:<messageId>"`.
Pagination uses both timestamp and message ID so records sharing a timestamp
are not skipped. `nextCursor` is null for an empty page; otherwise it identifies
the last record. Clients deduplicate by message ID.

`readAt` is omitted while the message is unread and is otherwise the epoch
millisecond at which the recipient read it. It is set for both directions of
the conversation: a message you received carries the moment you read it, a
message you sent carries the moment the peer read it.

### `POST /v1/conversations/{peer}/read`

Marks the messages `{peer}` sent to the caller as read.

```json
{"upToMessageId":"uuid"}
```

- `upToMessageId` is optional. Without it the whole conversation is marked;
  with it the mark covers that message and every earlier one in the
  conversation.
- The peer must exist (`404`); marking your own messages returns `400`.
- A message that is not part of the caller's conversation with `{peer}` is
  `404`, whether it names another user's message or the caller's own.
- The body carries no identity: the reader is the authenticated principal and
  the author is `{peer}`.
- Marking is idempotent. A message that is already read keeps its original
  timestamp; only unread messages change.

Response `200`:

```json
{"messageIds":["uuid"],"readAt":1760000005000}
```

`messageIds` lists the messages this call actually changed, in conversation
order. An empty list means nothing changed. `readAt` is the time of the mark.

Clients call this only while the conversation is on screen. A notification, a
background sync or a cursor catch-up must not mark anything read — a receipt
means a human looked at the message.

### `PUT /v1/conversations/{peer}/messages/{messageId}/reactions`

Adds or replaces the caller's reaction on a message (Phase 8, ADR-0011).
One reaction per author per message: reacting again replaces the emoji.

```json
{"emoji":"👍"}
```

- Any emoji codepoint is accepted (no curated set); the server stores the
  string as received.
- The message must belong to the caller's conversation with `{peer}`
  (`404` otherwise); a non-participant cannot react.
- Reacting when a reaction exists is an upsert: the row is replaced, never
  duplicated.

Response `200` — the full reaction list for the message after the change:

```json
[{"authorUsername":"bob","emoji":"👍","createdAt":1760000006000}]
```

Both participants also receive a `message-reaction` event on the realtime
stream.

### `DELETE /v1/conversations/{peer}/messages/{messageId}/reactions`

Removes the caller's reaction. Idempotent: removing when no reaction exists
is a success with no effect.

Response `200`: the reaction list for the message after the change
(normally empty).

Reactions are never sent on behalf of someone else: the author is the
authenticated principal, never a body field.

### `POST /v1/conversations/{peer}/read`

## Media (`/v1/media`)

Media endpoints share the Android bearer authentication and are exempt from
CSRF. Uploads are `multipart/form-data` with the parts `file` (binary) and
`peer` (username). Tomcat accepts request bodies up to 54 MB with files up to
52 MB; the service caps images at 16 MB and audio at 50 MB. Store limits
follow [ADR-0009](adr/0009-media-storage-s3.md) (in-memory store by default,
S3-compatible object storage when enabled).

### `POST /v1/media`

Authenticated multipart upload for the chat peer `{peer}` given as a form
field. The server records ownership (`owner` = authenticated principal,
`peer`), never client-supplied identity.

- Content types are allow-listed: images (`image/jpeg`, `image/png`,
  `image/webp`, `image/gif`) and audio (`audio/mpeg`, `audio/ogg`,
  `audio/mp4`, `audio/aac`, `audio/wav`). Anything else is `400`.
- Images: `400` above 16 MB; audio: `400` above 50 MB; oversize multipart
  requests fail with the servlet's limit error before reaching the handler.
- For images the server decodes dimensions and generates a JPEG thumbnail
  (long edge capped at 256px, never enlarged) at upload time.

Response `201`:

```json
{
  "id":"uuid",
  "owner":"alice",
  "peer":"bob",
  "contentType":"image/jpeg",
  "byteSize":123456,
  "sha256":"<hex>",
  "createdAt":1760000000000,
  "width":320,
  "height":200
}
```

`width`/`height` are null for non-image content. Sending this `id` with
`POST /v1/conversations/{peer}/messages` (`type=IMAGE`) attaches it to a
message; only a participant of the owning conversation may reference it.

### `GET /v1/media/{id}`

Streams the original bytes. Only the owner or the conversation peer may fetch
(`403` otherwise, `404` unknown id). Content type and length come from the
stored object; responses are `no-store` because bearer auth cannot use shared
caches.

### `GET /v1/media/{id}/thumbnail`

Streams the server-generated JPEG thumbnail for images (falls back to the
original for non-images). Same authorization as `GET /v1/media/{id}`.

## Realtime stream (`/v1/ws`)

Upgrade `GET /v1/ws` over WSS with the active user token in the
`Authorization` header using the `Bearer` scheme. Query tokens are rejected.
Invalid credentials close with `1008` immediately after upgrade.

- Multiple sessions per user receive events; a second connection does not
  disconnect the first.
- Server text frames retain the Android envelope:
  `{"type":"new-message","payload":"<JSON string>"}`. Decode `payload` as
  a message record containing `id`, `sender`, `recipient`, `content`,
  `createdAt`, `clientKey`, `type`, `mediaId`, `width`, `height`.
- Events go to sender and recipient. They accelerate updates but do not
  replace durable cursor synchronization after reconnection.
- `{"type":"presence","payload":"<JSON string>"}` goes to every other
  connected user when a user's derived presence changes: first/last stream
  opened or closed, or a SIP registration change seen by the periodic PBX
  check (about every 10 seconds). The payload is
  `{"username":"bob","presence":"online","updatedAt":1760000000000}`.
  Clients ignore event types they do not know.
- `{"type":"message-read","payload":"<JSON string>"}` goes to the author of the
  messages that were read. The payload is
  `{"messageIds":["uuid"],"readAt":1760000005000}` and lists the messages the
  reader has just read, in conversation order. The author shows them as read;
  the reader already knows from the response to its own mark.
- `{"type":"typing","payload":"<JSON string>"}` is an ephemeral fan-out
  (Phase 7): the client sends `{"typing":true}` or `{"typing":false}` over
  the socket; the server relays it to the sender's conversation partners as
  `{"peer":"bob","typing":true}`. Nothing is persisted. A peer with no
  recent message in either direction is not reachable via this signal.
- `{"type":"message-reaction","payload":"<JSON string>"}` (Phase 8) is a
  durable fan-out of a reaction change. Both conversation participants
  receive it; the client applies the add or remove to the cached message so
  chips update immediately without a poll. The payload is
  `{"messageId":"uuid","peer":"bob","authorUsername":"alice","emoji":"👍","action":"added|removed"}`;
  `peer` is the other participant (not the author), so each device can tell
  its own reaction apart from the peer's.
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
