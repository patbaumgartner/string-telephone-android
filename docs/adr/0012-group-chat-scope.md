# ADR-0012: Group chats — scope and one-to-one compatibility constraints

## Status

Accepted as a **scope decision only**. Group chat implementation is deferred
beyond the current roadmap horizon (`docs/feature-plan.md`); this ADR exists
so that the one-to-one designs being built now (ADR-0010 read receipts,
ADR-0011 reactions/replies/deletion, ADR-0009 media) do not hard-code
assumptions that would force rework when groups arrive.

## Context

WhatsApp and Signal both treat group messaging as a first-class citizen; this
project's entire messaging surface is pairwise. The comparison that produced
the feature roadmap identified groups as the single largest missing capability.

Groups are structurally different from everything currently built:

- **Identity model.** Today every conversation is exactly the authenticated
  pair `(sender, recipient)`; authorisation is "is the peer active". Groups
  need a membership relation, join/leave, and authorisation against
  membership instead of a fixed pair.
- **Message fan-out.** The `messages` table is pairwise and its unique index
  `(sender, recipient, client_key)` bakes one-to-one in. Group messages are
  one row visible to N members, or per-member copies — a schema fork.
- **Delivery state.** ADR-0010's `read_at` is a single timestamp because there
  is exactly one recipient. A group needs per-member read state, and the
  bubble tick semantics change (all members read vs. any).
- **Calls.** Asterisk managed dialplan is per-pair; group calls need a
  conference bridge or mesh, a different media path entirely.
- **Client model.** `ChatCache`, previews, unread counts, notifications and
  the whole repository API are keyed by a single `peer: String` throughout.

Options considered:

1. **Design everything pairwise now, redesign later.** Cheapest now, highest
   rework later — read receipts, reactions and deletion would all need
   migration.
2. **Build groups now.** Dominates the roadmap for months; the one-to-one
   core (receipts, media, voice) would be delayed by months for a feature
   with less immediate demand in a two-user self-hosted deployment.
3. **Record compatibility constraints now, defer implementation.** The
   one-to-one phases proceed, but each protocol/schema decision is checked
   against the group model so the later phase extends rather than rewrites.

## Decision

Take option 3: groups stay unimplemented, and the following constraints bind
the in-flight phases. Any phase that violates one of them must either fix the
design or record an explicit exception here.

1. **Conversation addressing.** New endpoints and tables must take a
   *conversation reference* rather than a bare peer username where practical
   (e.g. `/v1/conversations/{peer}/...` already reads as "conversation with
   this peer"). A later group phase adds a conversation ID without
   invalidating the pairwise shape.
2. **Message state is per message, not per pair.** ADR-0010's `read_at` and
   ADR-0011's tombstone live on the message row, keyed by message ID. For
   groups these become per-member state; implementations should keep read
   marking, reactions and deletion behind service methods that take the
   acting user, so the migration path is "extend to a membership table",
   not "redesign the endpoints".
3. **Events name their conversation.** New WebSocket payloads
   (`message-read`, `message-reaction`, `message-deleted`, `typing`) include
   the peer/conversation reference explicitly, never relying on sender-only
   context, so a group fan-out can reuse the envelope.
4. **Authorship stays derived.** Group identity attributes (groups a user
   belongs to) are added to the authenticated principal, never accepted from
   client bodies — the ADR-0003 authorship rule must survive groups.
5. **No `peer` column becomes a semantic group key.** The pairwise
   `(sender, recipient)` uniqueness stays; groups, when they come, get their
   own membership table rather than overloading `recipient` with group IDs.
6. **PBX stays pairwise until a call-phase decision.** Asterisk conferencing
   (confbridge) is the likely path but is deliberately out of scope here;
   nothing in the call engine assumes exactly two participants except the
   busy-decline policy, which is already recorded as intentional.
7. **Roadmap feature estimates assume one-to-one.** Read receipts, reactions,
   deletion and search designs in this document are explicitly pairwise;
   group variants are future work with their own ADR.

## Consequences

- The deferred group feature has a defined entry path: an implementation ADR
  extending ADR-0003's data model with a membership table and a conversation
  concept, reusing the event envelope and receipt patterns unchanged.
- Small cost paid now: endpoints take conversation-style paths and per-member
  state stays behind service seams — negligible against the value of not
  rewriting receipts/reactions/deletion later.
- Multi-device polish (Phase 13) and per-member state (Phase 18's
  user-conversation table) are natural precedents for the per-member group
  model, which reduces the later jump.
- Until groups are implemented, the maximum conversation size stays two and
  group-related requests (none exist yet) are impossible by construction —
  no speculative group code ships with this decision.
