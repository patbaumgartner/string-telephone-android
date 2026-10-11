# Companion API protocol

This document specifies the HTTP API that the Android app speaks with the
companion API server. It is the network boundary between the GPL Android app
and the AGPL server (ADR-0003, ADR-0004): no code is shared across it, so this
contract is what keeps the two licence domains separate.

The authoritative implementation is `server/api/src/main/kotlin/`; when this
document and the code disagree, fix the document in the same commit.

## Transport and authentication

- Base URL is user-provisioned onboarding input and must be `https://`
  (the onboarding screen rejects non-HTTPS addresses; HTTP is accepted only
  for explicit local testing).
- All `/v1/*` routes require the caller's API token as a bearer credential:

  ```
  Authorization: Bearer st_<token>
  ```

- The token is generated at provisioning time (see
  [operations.md](operations.md)), shown once, stored hashed (SHA-256) on the
  server, and encrypted on the phone (Android Keystore via security-crypto).
- The server derives identity and message authorship exclusively from the
  authenticated principal. Client-provided sender identity is ignored; a
  revoked or unknown token fails closed with `401`.
- Admin routes are separate and gated by a distinct admin token
  (≥ 24 characters, compared by hash).

## Conventions

- JSON bodies; unknown fields are ignored by the server.
- Errors are `{"error": "<reason>"}` with a meaningful HTTP status:
  `400` validation, `401` unauthorized, `404` unknown user, `409` conflict,
  `413` payload too large.
- All timestamps are epoch milliseconds (UTC, server clock).

## Health

### `GET /health`

Unauthenticated liveness probe.

```json
{"status": "ok"}
```

## User API (`/v1`)

### `GET /v1/me`

Returns the authenticated user.

```json
{"username": "alice", "displayName": "Alice"}
```

### `GET /v1/contacts`

Lists all other active users. In this prototype, `presence` is always
`"unknown"` from the API; the app derives presence from PBX registration
state through its own channel.

```json
{
  "contacts": [
    {
      "username": "bob",
      "displayName": "Bob",
      "presence": "unknown",
      "presenceUpdatedAtEpochMs": null
    }
  ]
}
```

### `POST /v1/conversations/{peer}/messages`

Sends a message to `peer`. Idempotent per `clientKey`: a retried send with
the same `(sender, recipient, clientKey)` triple returns the original message
(`INSERT OR IGNORE` on a database unique constraint) instead of duplicating
it. Clients should generate a random key per logical message and reuse it on
retry.

Request:

```json
{
  "recipient": "bob",
  "content": "hello",
  "clientKey": "0f0e0d0c-1a2b-3c4d-5e6f-708090a0b0c0"
}
```

Rules:

- `recipient` must equal the `{peer}` path parameter (`400 recipient
  mismatch` otherwise).
- `content` must be non-empty UTF-8 and at most 8192 bytes
  (`413 content size invalid`).
- `peer` must exist and be active (`404 no such user`); you cannot message
  yourself (`400 cannot message yourself`).
- The server ignores any implied sender: authorship comes from the bearer
  token, never from the body.

Response `201 Created`:

```json
{
  "id": "…uuid…",
  "sender": "alice",
  "recipient": "bob",
  "content": "hello",
  "createdAt": 1760000000000,
  "clientKey": "…"
}
```

### `GET /v1/conversations/{peer}/messages`

Cursor-paginated history of the two-party conversation (both directions),
newest first.

Query parameters:

- `cursor` (optional) — opaque cursor from a previous page's `nextCursor`.
- `limit` (optional) — page size, clamped to 1..200 (default 50).

The cursor is URL-safe Base64 of `"<createdAtEpochMs>:<messageId>"`; clients
treat it as opaque and pass back `nextCursor` verbatim to fetch the next
(older) page.

Response `200 OK`:

```json
{
  "messages": [
    {
      "id": "…",
      "sender": "alice",
      "recipient": "bob",
      "content": "hello",
      "createdAt": 1760000000000,
      "clientKey": "…"
    }
  ],
  "nextCursor": "MTc2MDAwMDAwMDAwMDptc2ctaWQ"
}
```

`nextCursor` is `null` when the page is exhausted. After a reconnect, a
client replays its stored cursor to catch up without duplicates.

## Admin API (`/v1/admin`)

All admin routes require the admin token (min. 24 characters, env
`API_ADMIN_TOKEN`; the server stores only its SHA-256 hash):

```
Authorization: Bearer <admin-token>
```

| Route | Body | Effect |
| --- | --- | --- |
| `POST /v1/admin/create-user` | `{"username": "bob", "displayName": "Bob"}` | Creates the user and a SIP secret; responds `201` with the API token **shown once**. Username must match `[a-z][a-z0-9-]{0,31}`; duplicate → `409`. |
| `POST /v1/admin/revoke-user` | `{"username": "bob"}` | Deactivates the account (tokens stop working). `404` if unknown. |
| `GET /v1/admin/list-users` | — | Lists users with `username`, `displayName`, `active`. |

The same operations are available as the operator CLI
(`stringtelephone-provision`); see [operations.md](operations.md).

## What is not in the protocol (yet)

- **No end-to-end encryption.** HTTPS protects transport only; the server can
  read messages (ADR-0003). Never claim E2EE.
- **No user-initiated remote delete.** v1 keeps server history; clients clear
  only their local cache.
- **No WebSocket stream yet.** ADR-0003 plans an authenticated stream for
  new-message and presence events; clients currently poll by cursor.
- Presence is a static `"unknown"` from this API in the prototype; the app
  derives presence from PBX registration state out of band.
