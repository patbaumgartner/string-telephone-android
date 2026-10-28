# ADR-0010: Message delivery states and read receipts

## Status

Accepted (implemented in Phase 5, 2026-10-21). Read receipts are **always on
and mutual** — an opt-out was considered and rejected because it needs a user
preference, server-side sync, and asymmetric-visibility UI; revisiting that is
a protocol change.

## Context

The app shows only local send state today: `PENDING` (optimistic), `SENT`
(server acknowledged) and `FAILED`. Users cannot tell whether the other side
received or read a message. Reading a push or in-app notification must not
count as reading the message — only displaying it in the open conversation
counts, matching user expectations from mainstream messengers.

Requirements and constraints:

- **Sender-visible states:** sent (server stored it), delivered (the
  recipient's app has the message), read (the recipient viewed it in the
  conversation).
- **Read semantics:** a message becomes read when the recipient has the
  conversation open while the message is displayed. Notification arrival,
  background sync, or cursor catch-up alone must never mark a message read.
- **Privacy:** read receipts are mutual — seeing someone's read state requires
  sending your own. There is no setting to disable them; both participants in
  a one-to-one conversation always exchange them.
- **Offline catch-up:** a recipient who was offline reads history on open; the
  sender must learn about it after their own reconnect (durable state, not
  just a transient event).
- The server already owns message state (`Messages`, SQLite/PostgreSQL per
  ADR-0008) and fans out events over the WebSocket (`WsHub`). Client identity
  comes from the authenticated principal.

Options considered:

1. **Receipts over SIP (MESSAGE or INFO through Asterisk).** Couples chat
   state to the PBX, bypasses the API's persistence, and breaks the
   "WebSocket accelerates, REST is authoritative" model of ADR-0003.
2. **Server-persisted per-message read state + WebSocket events.** Durable,
   queryable on history fetch, consistent with the existing architecture.

## Decision

Track delivery and read state **per message on the server**, exposed through
the existing REST and WebSocket channels.

- **Persistence.** Messages gain read state on the server: `read_at` (nullable
  epoch ms) set by the recipient's authenticated requests. Delivery state is
  derived, not stored: a message is "delivered" once the recipient's app has
  it, which the server learns from the recipient's history fetch or realtime
  stream acknowledgement; the first cut collapses sent→delivered and shows
  sent → read only if keeping a separate durable delivered state proves
  unnecessary.
- **Marking read.** `POST /v1/conversations/{peer}/read` with an optional
  `upToMessageId` marks all the peer's messages up to that ID as read. The
  endpoint is called only when a human actually saw the conversation: from
  the foreground chat screen while messages are rendered, or from the
  notification's explicit "Mark as read" action (Phase 14) — the user acted
  on the notification itself, which is a deliberate act, not an automatic
  side effect. Notification arrival, background sync and cursor catch-up
  still never mark anything read on their own.
- **History.** `GET /v1/conversations/{peer}/messages` responses include
  `readAt` (null when unread) so a freshly syncing client reconstructs ticks
  without extra calls.
- **Realtime.** When read state changes, the server emits
  `{"type":"message-read","payload":{"messageIds":[...],"readAt":...}}` to the
  conversation participants over `/v1/ws`. Events accelerate UI updates; the
  REST history remains the source of truth after reconnect.
- **Client model.** `SendState` becomes a richer delivery state machine
  (`PENDING`, `SENT`, `READ`, `FAILED`; `DELIVERED` reserved for when/if
  durable delivery tracking lands). The chat bubble shows: clock (pending),
  one tick (sent), double tick (read, brand colour), warning (failed).
- **Grouping.** Read marks are per message; the UI lights the double tick on
  every read message, not only the last.

### Implementation notes

- The read state lives in the `messages` row (`read_at`, added by the
  `V4__message_read_state.sql` migration). One atomic `UPDATE` marks the
  unread messages of the conversation; a message that is read twice keeps the
  timestamp of the first read, so a receipt never moves.
- The update returns the ids it changed, ordered by creation time and id, so
  the response lists them in conversation order (`UPDATE ... RETURNING` alone
  gives no row order).
- The server derives both sides of the mark from the authenticated principal:
  the caller is the reader, `{peer}` is the author. A message outside that
  conversation is `404`, so a third party cannot name someone else's message.
- The client keeps a read state sticky. A history page that was fetched before
  the receipt still says "unread", and merging it must not undo a tick the
  user already sees.
- The client merges on explicit rules, not on the ordinal of the state enum:
  a read state never loses to a later page, and a failure never loses to a
  success.

## Consequences

- The protocol gains one endpoint and one event type; `docs/protocol.md`
  documents both before any app build advertises read receipts.
- Group chats would need per-member read state (one `read_at` per member
  instead of one per message); [ADR-0012](adr/0012-group-chat-scope.md)
  records the constraint that read marking stays behind a service method
  taking the acting user so this extension does not redesign the endpoint.
- Read receipts are privacy-relevant: they are always-on and mutual, and the
  UI must not imply otherwise. An opt-out was considered and rejected for now
  (see Status); adding one later would be a protocol change.
- History pagination responses grow by one field; unknown-field tolerance
  (already the convention) keeps older clients working.
- The unread badge logic (local) and the read-receipt logic (server) are
  independent: marking a conversation read locally clears the badge, while the
  server-side mark informs the *sender*. Both happen on the same user action
  (opening the conversation).
- Tests must cover the timing traps: a message fetched by cursor sync while
  the chat is closed must not become read; read state set offline must reach
  the sender after reconnect.
