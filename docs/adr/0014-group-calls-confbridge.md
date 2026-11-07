# ADR-0014: Group calls — Asterisk confbridge with per-call bridges

Date: 2026-10-31
Status: Accepted

## Context

Phase 19 shipped group chats (ADR-0012); group calls were deliberately left
out (constraint 6: "PBX stays pairwise until a call-phase decision"). The
media path is anchored: every call already relays through Asterisk 22
(`direct_media=no`, ADR-0001/ADR-0013), so there is no peer-to-peer path to
preserve and no new NAT surface to introduce. liblinphone (ADR-0002) dials a
SIP URI; it has no native multi-party notion on this anchored path.

Three structures were considered:

1. **Full mesh of pairwise calls.** N×(N−1)/2 SIP sessions per group call,
   each client mixing audio itself. Asterisk 22 has no client mixing help;
   liblinphone's Android builds do not expose a software mixer; battery and
   bandwidth scale quadratically; and the busy-decline policy (one call at a
   time per device) makes joining a mesh impossible without re-architecting
   the engine.
2. **A permanent conference extension per group.** The dialplan gains a
   `confbridge` target per group id. Groups are chat-state, created and
   disbanded freely; bridges are call-state, resource-bound. Permanent
   bridges leak resources after disband, conflict with the managed-files
   model (every group edit would need a PBX reconcile), and give members a
   dialable target even when no call is happening.
3. **A per-call conference bridge, managed by the API.** The API records a
   `Confbridge` on demand when a member starts a group call and tears it
   down when the last participant leaves. The dialplan gets one static,
   generic pattern that routes any bridge id; membership stays a
   server-side check. No per-group PBX state, no leak, no extra reconcile.

## Decision

Take option 3, with the following rules:

1. **Any member may start a group call** (the WhatsApp model). A call is
   ephemeral shared state, not group administration; the admin-only rule
   stays with rename/add/remove/disband.
2. **One bridge per call, created on demand.** `POST
   /v1/groups/{groupId}/calls` asks the API to open a bridge; the API
   generates a random bridge id (`conf-<uuid>`), registers the bridge in
   PostgreSQL (`group_calls` + `group_call_participants`). The bridge itself
   materializes when the first channel enters `Confbridge(<name>)` — Asterisk
   22 has no create AMI action — and is destroyed when its last participant
   leaves (`confbridge kick <bridge> all` over the AMI `Command` action; an
   empty confbridge destroys itself). A crashed/stale bridge is reclaimed by
   the existing PBX retry loop.
3. **The dialplan is static and generic.** `extensions-managed.conf` gains
   one pattern: `exten => _[c][o][n][f]-.,1,Confbridge(${EXTEN})` — the bridge id
   travels in the dialed extension. Adding/removing groups never touches the
   PBX; only this one-time pattern addition does.
4. **Membership is the only authorisation, enforced server-side.** The app
   never dials a bridge id it invented: it asks the API, which checks live
   membership (404 for non-members, as everywhere in groups) and returns the
   bridge id. Joining uses the same check. Non-member invite paths do not
   exist.
5. **Ring all at once; skip busy members.** The caller's INVITE reaches the
   bridge, and the fan-out of ringing happens through the existing realtime
   stream: every other member's devices receive a `group-call-invite` frame
   and ring natively. A member already in any call (pairwise or group) does
   not receive the invite — the server knows from the caller's presence that
   they are on a call only via SIP, so the busy check is client-honest: a
   device that is already in a call auto-declines with `Busy` (existing
   one-call-at-a-time engine rule, ADR-0006), which reads to the caller as
   "unavailable", exactly like the pairwise busy policy.
6. **First answer stops the ringing.** The join/leave REST records drive the
   fan-out: the API sends a `group-call-state` frame to the group so
   remaining devices stop ringing when someone answered (or when every
   device declined/no-answered, ending the call).
7. **Video in groups is VP8 through the bridge, best effort.** Confbridge
   bridges `video_mode=sfu` so every sender's VP8 stream is relayed to the
   others without transcoding or mixing; the app shows the active speaker
   (last `video_sources_updated` change) full-bleed, matching the pairwise
   call screen's single-remote-surface model. There is no layout grid in
   this phase; a member who sends nothing is audio-only, exactly like a
   camera-off pairwise call. A deployment that sets `video = none` gets
   audio-only bridges (`video_mode` omitted), and a member whose device
   cannot or may not send video keeps the pairwise degradation rules
   (ADR-0013): permission denied degrades to audio, backgrounding removes
   the stream, no camera FGS exists.
8. **Pairwise calls are untouched.** The pairwise dialplan, the busy policy
   and the call engine paths keep working exactly as before; group calls are
   a new target (`callUri` with a `conf-` prefix), not a change to the
   existing flow.
9. **Background and permission rules carry over unchanged** (ADR-0006):
   ringing needs a live process, answering opens the app through the
   microphone gate, the microphone FGS covers the call, and there is no
   camera FGS — video only while the call screen is visible.

## Consequences

- Group call media is server bandwidth, like every call here; deployments
  must size for N concurrent callers' audio (plus VP8 when video is on).
  The operations guide documents the sizing.
- The bridge lifecycle reuses the AMI the gateway already speaks: `Command`
  (`confbridge kick`) for destroy and `CoreShowChannels` for the member
  cross-check — no new AMI actions exist to grant. A PBX that cannot run
  confbridge keeps calls from starting (explicit 503, like every PBX
  operation), never silently.
- The `group-call-invite`/`group-call-state` frames extend the Phase 19
  group envelope; clients that do not know them ignore them (existing rule),
  so an old app member of a group simply never rings and learns about the
  call from the chat.
- One active group call per group at a time (the bridge is the call); a
  second starter idempotently gets the running call back (200 with the same
  bridge id). Members may join an existing call while it runs.
- The prototype (`scripts/test-prototype.sh`) gains a bridge lifecycle
  check: bridge created on invite, destroyed on last leave, and a
  non-member INVITE to a bridge id is rejected by membership, not by
  obscurity.
- No E2EE: group call media is SRTP-encrypted hop-by-hop (SDES on the
  anchored path), decrypted inside Asterisk's bridge, like pairwise calls.
  The "Deferred indefinitely" entry for encryption covers this.
