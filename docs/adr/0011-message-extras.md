# ADR-0011: Message reactions, replies and deletion semantics

## Status

Accepted (planned; phases follow the media and receipts phases in
`docs/feature-plan.md`).

## Context

Messaging currently supports plain text only. Three familiar interactions are
missing: emoji reactions on a message, quoting a message in a reply, and
deleting a message. All three mutate or annotate existing messages rather
than adding a new message kind, so they share the message-state model and the
same synchronisation questions. Deciding them separately would fragment the
message-state design; deciding them together keeps one coherent model.

Existing constraints (ADR-0003/0008/0010): messages are server-persisted with
opaque IDs, authorship is derived from the authenticated principal, the
WebSocket accelerates but REST is authoritative, and there is no end-to-end
encryption — the server sees message content.

Options considered per interaction:

1. **Reactions as ordinary messages** (a `"reaction"` message referencing a
   target). No schema change, but history pagination, unread counts and
   previews would all need reaction-aware filtering everywhere, forever.
2. **Reactions in a dedicated table keyed by (message, author).** Clean
   aggregation, no history pollution; needs one table and two endpoints.
3. **Reply as client-side only** (quote text copied into a new message).
   Simple, but the quote never updates if the original is deleted and cannot
   be validated to belong to the same conversation.
4. **Delete as hard delete.** Breaks the tombstone requirement: the other
   side would keep a message the server no longer has, and sync would
   resurrect it.

## Decision

Ship reactions, replies and deletion as **server-side message annotations and
state**, sharing one data-model change.

- **Reactions.** A `reactions` table keyed by `(message_id, author_username)`
  holding one emoji string (any emoji from the keyboard, no curated set —
  decided). Adding and removing are separate authenticated requests on the
  message; both fan out over the WebSocket as `message-reaction` events.
  One reaction per author per message; reacting again replaces the emoji,
  long-press toggles off. Every participant may react.
- **Replies.** `reply_to` on the message record: a nullable message ID that
  must reference a message in the same conversation (validated server-side;
  self-references and cross-conversation references are rejected with `400`).
  The quoted snapshot (author, text or thumbnail) is rendered by the client
  from the referenced message when available and from the cached copy when the
  original was locally deleted.
- **Deletion.** Two operations:
  - *Delete for me* is client-local only (hide in the local cache; the server
    record is untouched).
  - *Delete for both sides* writes a tombstone: `deleted_at` and
    `deleted_by` on the message, broadcast as a `message-deleted` event.
    Content is cleared from the message record at the server; history
    endpoints return the tombstone shape. No time window in the first cut.
- **Previews.** Conversation previews and notification text must respect all
  three: a reaction alone does not change the preview text; a tombstoned
  message previews as "message deleted"; a reply previews the reply text.
- **Ordering.** Annotations and tombstones travel as WebSocket events for
  liveness but are reconstructed from REST history after reconnect; events
  accelerate, REST remains authoritative (same split as ADR-0003).

## Consequences

- The message model gains nullable `reply_to`, a `deleted_at` marker, and a
  one-to-many reactions relation; history responses and the WebSocket payload
  for messages grow accordingly. Unknown-field tolerance keeps older clients
  working (they render reactions as nothing and deleted messages as normal
  text until the tombstone fetch catches up — acceptable for a self-hosted
  pair upgrade, and both clients ship together in practice).
- Voice messages need no ADR: they are a media type over the ADR-0009
  pipeline plus recording/playback UI, with no new protocol semantics beyond
  the media message type already reserved there.
- Moderation note: deleting for both sides clears server-side content, but
  the server operator could still have backups; the UI must not claim
  cryptographic erasure.
- Group compatibility: per [ADR-0012](adr/0012-group-chat-scope.md),
  reactions and deletion state is keyed by message ID and mutated through
  service methods that take the acting user; event payloads name the
  conversation explicitly. Group chats would extend these to membership
  semantics rather than redesign them.
- Tests must cover: reaction replace/toggle, reply to a deleted message,
  tombstone surviving cursor sync, and a second device learning about a
  deletion done elsewhere.
