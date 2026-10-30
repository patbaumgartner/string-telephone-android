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
unknown). PBX state is cached for about five seconds. An offline contact
also carries `lastSeenEpochMs`: the epoch millisecond at which their last
realtime session disconnected (or the account was disabled), truncated to
the minute. It is null (omitted) when the contact is online or has never
connected — last seen means "was last reachable", never "last clicked",
and it is derived from connection state only, never app activity.

```json
{
  "contacts": [{
    "username":"bob",
    "displayName":"Bob",
    "presence":"online",
    "presenceUpdatedAtEpochMs":1760000000000,
    "lastSeenEpochMs":1759901200000
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

#### Reply to a message

Send `{"type":"TEXT","recipient":"bob","content":"...","replyToId":"<id>",
"clientKey":"..."}` to quote an existing message (ADR-0011, Phase 9). The
`replyToId` is nullable and optional; when present it must reference a
message that belongs to the caller's conversation with `{peer}` — a
self-reference, a cross-conversation reference or an unknown id is `400`. The
quoted snapshot is rendered by the client from the referenced message when it
is in the local cache and from nothing otherwise. `replyToId` appears in
history responses and in the `new-message` event payload; it is omitted (null)
for non-replies.

#### Delete a message

`DELETE /v1/conversations/{peer}/messages/{messageId}` tombstones a message
for both sides (ADR-0011, Phase 10). Either conversation participant may
delete; the deleter is derived from the authenticated principal, never from
the body. The server clears the message content and stamps `deletedAt` and
`deletedBy`; the row itself stays so pagination, ids and reply references
remain stable — sync can never resurrect the text.

- Response `200` is the tombstone shape: `content` is `""`,
  `deletedAt` is the deletion epoch millisecond, `deletedBy` is the deleter's
  username. `404` unknown message, `403` when the message is not in the
  caller's conversation with `{peer}`.
- The call is idempotent: deleting an already-deleted message returns the
  first tombstone unchanged.
- A `message-deleted` frame is pushed to both participants
  (see [Realtime events](#realtime-events-websocket)).
- *Delete for me* has no endpoint: it is a client-local hide with no server
  change, so the peer keeps the message and a history reload brings it back.
- A tombstoned message still satisfies reply references; the client renders
  the quote from its cached copy (ADR-0011).

#### Edit a message

`PUT /v1/conversations/{peer}/messages/{messageId}` re-stores the content of
one message (Phase 17). Only the author may edit (the server derives the
editor from the authenticated principal); there is no time window,
consistent with the deletion decision above.

- Body is `{"content":"new text"}`; the same 8 KiB content cap as sends
  applies, and a blank body is a `400`.
- `403` when the caller is not the author, `404` unknown message or a
  message outside the caller's conversation with `{peer}`, `409` when the
  message is tombstoned (deletes take precedence) or not a text message.
- Response `200` is the updated message record including `editedAt`.
- The read receipt is preserved: an edit never un-reads a message (ADR-0010
  — a receipt means a human looked at the message).
- A `message-edited` frame is pushed to both participants
  (see [Realtime events](#realtime-events-websocket)).
- Edited text bubbles show an "edited" hint next to the time; the bubble's
  content description announces it.

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

#### Voice messages

Send `{"type":"VOICE","recipient":"bob","mediaId":"<id>","clientKey":"..."}`.
The `content` field stays empty for voice messages.

- `mediaId` must reference audio media uploaded to `POST /v1/media` by the
  caller for this peer (`404` unknown media, `403` when the record belongs to
  another conversation, `400` when it is not `ready`).
- `durationMs` (integer milliseconds, 1–600 000) and `waveform` (comma-separated
  integer amplitudes `0`–`100`, at most 64 samples, up to 255 characters) are
  required body fields; the server clamps each amplitude to `0`–`100`, caps the
  sample count at 64 and stores the result.
- The server derives the author from the authenticated principal and stores
  the duration and waveform; clients cannot fake either.
- Audio media is capped at 50 MB server-side.

Voice fields in responses: `type` (`"TEXT"` default, `"IMAGE"` or `"VOICE"`),
`mediaId` (null for text), `durationMs` (integer milliseconds, absent when
unknown), `waveform` (comma-separated integer amplitudes `0`–`100`, at most 64
samples, absent for text/image). The same
fields appear in history responses and in the `new-message` event payload.

The cursor is URL-safe Base64 of `"<createdAtEpochMs>:<messageId>"`.
Pagination uses both timestamp and message ID so records sharing a timestamp
are not skipped. `nextCursor` is null for an empty page; otherwise it identifies
the last record. Clients deduplicate by message ID.

### `GET /v1/conversations/{peer}/search?q=`

Full-text search within the authenticated pair's conversation (Phase 16),
newest first. The response shape is identical to the history endpoint
(`MessagesPageDto` with `nextCursor`), so clients reuse the same cursor
codec. `limit` behaves the same (default 50, clamped to 1-200); a blank `q`
is a `400`; an unknown peer is `404`; a caller outside the pair finds
nothing (the scoping predicate is the same as history's).

Matching is case-insensitive full-text over the message content via a
generated `search_text` tsvector (V11, PostgreSQL `simple` configuration:
case-folded, whitespace-normalised, diacritics kept as typed). The query is
parsed with `websearch_to_tsquery`, so quoted phrases, `OR` and
minus-exclusions work and a malformed query yields no matches rather than an
error. Tombstoned messages have no content and never match. A missing `q`
returns `400`.

```
GET /v1/conversations/bob/search?q=pineapple
```

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

### `PUT /v1/conversations/{peer}/messages/{messageId}`
Re-stores the content of one message (Phase 17, ADR-0011 pattern: no time
window, deletes take precedence). Only the author may edit; editing a
tombstoned message or a non-text message is a `409`. The read receipt is
preserved (an edit never un-reads a message, ADR-0010). A `message-edited`
WS event fans to both participants so every device re-renders the bubble
without a history refresh.

```json
PUT /v1/conversations/bob/messages/<id>
{"content": "hello bob"}

200  {"id":"<id>","content":"hello bob","editedAt":1760000001000,…}
400  (blank/too-large content)
403  (not the author)
404  (no such message)
409  (tombstoned or non-text)
```

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

## Calls (SIP media)

Calls never traverse the companion API. The app registers directly with the
managed Asterisk endpoint (SIP/UDP in development, SIP/TLS with mandatory
SRTP in release builds) and media is relayed by Asterisk
(`direct_media=no`); there is no ICE configuration on the PBX
([ADR-0013](adr/0013-video-calls.md)).

- **Audio** follows the admin codec preference order; managed endpoints
  publish `allow=<codecs>` (for example `allow=opus,ulaw`).
- **Video** is VP8 only. With `video = vp8` the endpoint publishes
  `allow=<codecs>,vp8`, and a video INVITE carries `m=video ... RTP/AVP 96`
  with `a=rtpmap:96 VP8/90000`. With `video = none` the offer is answered with
  `m=video 0` and the call continues as audio. Other video codecs (H264,
  VP9, AV1) are not negotiated.
- **Camera off** re-INVITEs `videoDirection=RecvOnly` (the peer stays
  visible); **leaving the call screen** re-INVITEs with video disabled
  entirely while audio keeps running.
- The RTP range is `10000-10100/UDP` for both streams of a video call (audio
  and video, each with RTCP). Nothing about calls is stored by the API; the
  server only provisions SIP identities and records call state for
  administration.

## Realtime stream (`/v1/ws`)

Upgrade `GET /v1/ws` over WSS with the active user token in the
`Authorization` header using the `Bearer` scheme. Query tokens are rejected.
Invalid credentials close with `1008` immediately after upgrade.

- Multiple sessions per user receive events; a second connection does not
  disconnect the first. Events fan out to **all** of a user's sessions, so
  with several devices signed in, every device sees every event.
- Server text frames retain the Android envelope:
  `{"type":"new-message","payload":"<JSON string>"}`. Decode `payload` as
  a message record containing `id`, `sender`, `recipient`, `content`,
  `createdAt`, `clientKey`, `type`, `mediaId`, `width`, `height`,
  `replyToId`.
- Events go to sender and recipient. They accelerate updates but do not
  replace durable cursor synchronization after reconnection.
- `{"type":"presence","payload":"<JSON string>"}` goes to every other
  connected user when a user's derived presence changes: first/last stream
  opened or closed, or a SIP registration change seen by the periodic PBX
  check (about every 10 seconds). The payload is
  `{"username":"bob","presence":"online","updatedAt":1760000000000,
  "lastSeenAt":1759901200000}`. `lastSeenAt` is present only when the user
  is offline: the millisecond their final realtime session disconnected
  (minute-granular, null while online or never connected). Clients ignore
  event types they do not know.
- `{"type":"message-read","payload":"<JSON string>"}` goes to the author of the
  messages that were read **and to the reader's other sessions**, so every
  device of an account converges on the same read state (Phase 13). The
  payload is
  `{"messageIds":["uuid"],"readAt":1760000005000}` and lists the messages the
  reader has just read, in conversation order. The author shows them as read;
  a reader device that did not perform the mark applies it idempotently —
  clearing its unread badge and cancelling that conversation's notification.
  Applying a receipt twice is harmless.
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
- `{"type":"message-deleted","payload":"<JSON string>"}` (Phase 10) is a
  durable fan-out of a tombstone. Both conversation participants receive it;
  the client renders the placeholder immediately without a history refresh.
  The payload is
  `{"messageId":"uuid","peer":"alice","deletedBy":"bob","deletedAt":1760000000000}`;
  `peer` is the other participant (not the deleter), `deletedBy` names who
  deleted the message. Events accelerate but REST history remains
  authoritative: after a reconnect the tombstone also appears in history
  responses (`deletedAt`/`deletedBy` on the message).
- `{"type":"message-edited","payload":"<JSON string>"}` (Phase 17) is a
  durable fan-out of an edit. Both conversation participants receive it;
  the client re-renders the bubble with the new content and the "edited"
  hint immediately without a history refresh. The payload is
  `{"messageId":"uuid","peer":"bob","content":"hello","editedAt":1760000001000}`;
  `peer` is always the recipient (only the author edits), `editedAt` is the
  edit epoch millisecond. The read receipt is untouched, and events
  accelerate but REST history remains authoritative (`editedAt` also
  appears on the message in history responses).
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
| `POST /v1/admin/pbx/settings` | `{"codecs":"opus,ulaw","ringSeconds":30,"maxContacts":2,"externalAddress":"","localNetwork":"","symmetricMedia":true,"video":"vp8"}` | Validates, persists, applies and verifies settings. `video` is `vp8` or `none`. |
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
