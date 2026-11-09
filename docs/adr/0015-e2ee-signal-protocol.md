# ADR-0015: End-to-end encryption — Signal Protocol via libsignal

## Status

Accepted (2026-11-07). Implementation as Phase 22. Previously deferred
indefinitely by the roadmap's "Deferred indefinitely" table pending demand and
its own ADR — demand confirmed, this is the ADR.

## Context

Every message, edit, reaction payload, and media object today is stored
plaintext on the companion API and is readable by anyone with server access:
the DBA, the host admin, a compromised host. Transport security is TLS
(ADR-0003) and the PBX media path is SRTP (ADR-0001), but the server sees
everything.

The roadmap deferred E2EE "indefinitely" because it changes client, server,
and protocol fundamentally. This ADR resolves that deferral.

## Decisions

### 1. Use the reference implementation, not a reimplementation

Writing X3DH + Double Ratchet by hand in Kotlin on JDK primitives would repeat
the mistakes the protocol exists to fix — a subtle nonce-reuse or RNG mistake
is catastrophic and invisible. Signal publishes the reference implementation
with Android bindings:

- `org.signal:libsignal-android` on Maven Central (0.86.5 at the time of
  writing), Kotlin-first API, native libs for armeabi-v7a, arm64-v8a, x86,
  x86_64 — matching the ABI set we ship.
- Battle-tested in Signal itself and actively maintained; AGPL-3.0.

Alternatives rejected:

- **Own X3DH + Double Ratchet on JDK crypto** — hand-rolled protocol
  implementations are exactly the risk E2EE is meant to remove.
- **MLS (RFC 9420)** — modern, but no Android-stable Kotlin binding, and the
  per-member fan-out model below makes MLS's group efficiency moot for a
  32-member cap.

### 2. Licensing: the app module becomes AGPL-3.0 in effect

`app/` links linphone-sdk (GPL-3.0, ADR-0002/0004). libsignal-android is
AGPL-3.0. GPLv3 §13 lets a GPL-3.0 work combine with AGPL-3.0 parts; the
combined work must carry the terms of *both*, so distributing the app means
supplying Corresponding Source under AGPL-3.0 (GPLv3 grants survive for the
GPL parts). Accepted consequence: the app's effective license is AGPL-3.0 and
README/LICENSE are updated in the phase. The `server/` module already is
AGPL-3.0 (ADR-0004) and still never links Android or libsignal code.

### 3. Trust model: safety numbers, first-use trust

- Each identity key pair is generated on-device and never leaves it; the
  private key is stored in the Android Keystore-backed secure store
  (`SecureCredentialStore` seam).
- The server acts as a **dumb key directory**: it stores public identity keys
  and one-time/signed prekeys and relays opaque ciphertext. It can no longer
  read message content, and search becomes client-side (see consequences).
- Trust on first use: contact profiles expose a **safety number** (the
  fingerprint hash of both identity keys, 60 digits like Signal). Users
  compare it out of band; a changed safety number after re-install shows a
  prominent warning banner in the chat. Until the user acknowledges it,
  libsignal refuses new handshakes and decrypts for that peer (sends fail
  honestly, incoming messages render a placeholder); acknowledging the banner
  ("I verified it") accepts the peer's currently published identity key so new
  sessions process again.

### 4. Sessions: X3DH + Double Ratchet per (user, device) pair

- Registration uploads an identity key, a signed prekey, and a batch of
  one-time prekeys (replenished by the client when the server reports a low
  count).
- A send to a peer with no session consumes one of the peer's one-time
  prekeys (X3DH) and seeds a Double Ratchet session.
- Multi-device stays out of scope for this phase: the API is single-device
  today (maxContacts on the PBX side is about SIP, not the companion API), so
  sessions are per user pair. Device management is future work and must not
  be half-built now.

### 5. Group messages: per-member fan-out, no sender keys

Group messages are encrypted once per recipient member (capped at 32 members)
with that member's pairwise ratchet session — the same machinery as pairwise,
just N sends per message. Server-side fan-out of one plaintext blob is
replaced by N ciphertext envelopes addressed to each member.

Rejected: **sender keys** (one sender distributes a group sender key; members
encrypt once) — significantly more protocol machinery (sender-key distribution
ratchet, per-sender chain tracking) for an efficiency win that is irrelevant
at 32 members and that the per-member fan-out provides more simply with
identical security properties. If real load ever demands it, sender keys are
an additive ADR on top.

### 6. Media and attachments

- Images/voice are encrypted **on-device before upload**: AES-256-GCM with a
  per-attachment random content key and nonce; the content key travels inside
  the E2EE envelope to each recipient. The server stores opaque encrypted
  bytes and can no longer generate thumbnails (see consequences).
- Thumbnails are generated **on-device at attach time** (long edge 256px,
  same cap as the old server-side path) and are themselves encrypted
  attachments.

### 7. Calls stay PBX-relayed — documented limitation

Voice/video call media continues to relay through Asterisk with hop-by-hop
transport security (TLS signalling, SRTP media; ADR-0001/0013). Call media is
NOT end-to-end encrypted. Replacing this needs SFrame/insertable-stream
encryption negotiated between the two apps, which would break the
PBX-in-the-path design (ADR-0001) and the transcode/recording capabilities
the PBX provides. Documented as a limitation in README and protocol.md; an
SFrame ADR is future work only if there is demand.

### 8. What the server can no longer do — consequences accepted

- **Server-side search** (V11 tsvector, `Messages.search`) becomes impossible:
  search moves **client-side** over the local cache (the `ChatCache` seam is
  already framework-free and unit-tested; the conversation-list search field
  now filters the local store instead of calling `/v1/conversations/{peer}/messages?q=`).
  The V11 tsvector column stays (forward-only migrations, ADR-0008 rule) but
  is populated with a placeholder for encrypted rows and no longer queried by
  the API.
- **Server-side thumbnails** (V5/MediaService decode) become impossible:
  thumbnails are made on-device before upload (see 6).
- **Sender-derived previews** for conversation-list snippets remain possible:
  previews move to the first bytes of the *decrypted* content cached locally
  (the app already renders previews from the local cache, Phase 15).
- Notifications: full message text can no longer be put into notifications
  decrypted-safe by the server — the app already renders notifications from
  locally-decrypted content (session/SessionController), so this is unchanged
  behaviour, now guaranteed.

## Consequences

- One-time prekey replenishment, session establishment, and ratchet advances
  happen entirely on-device; the server stores public material and opaque
  ciphertext only.
- The protocol gains: key-directory endpoints (`/v1/keys/**`), an envelope
  format on send, a prekey-batch fetch, and a safety-number surface. All are
  additive; the plaintext paths stay for migration and are removed in the
  same phase once both apps ship (no dual-write period across releases —
  one-to-one compatibility is between app versions of the same era, ADR-0012
  constraint 2).
- Messages already stored plaintext stay readable in history until the
  migration marks them legacy; new messages are encrypted from the first
  E2EE-enabled app build.
- Group fan-out cost is N ciphertexts per message; acceptable at the 32-member
  cap (ADR-0012).
- Call media is not E2EE (limitation, see 7).

## Implementation sketch (Phases 22–23)

One theme per commit, so each stays reviewable; this ADR is the umbrella.

- **Phase 22 — E2EE for messages (pairwise + groups, text and edits)**:
  - **Core** (`core/`): envelope DTOs (`KeyBundleDto`, `PrekeyResponse`,
    `EncryptedEnvelope` with `type: PREKEY|MSG`), safety-number derivation
    (SHA-512-iterated fingerprint display format over both identity keys,
    JDK crypto only — no libsignal in `core/`, the engine is an app seam).
  - **Server**: V17 migration (`identity_keys`, `signed_prekeys`,
    `one_time_prekeys` keyed by username; `messages.content_encrypted`
    marker so legacy plaintext rows stay distinguishable); key-directory
    endpoints `PUT /v1/keys`, `GET /v1/keys/{peer}`,
    `GET /v1/keys/{peer}/one-time` (atomic consume); the send paths accept
    envelopes and fan out N envelopes for groups. The server validates only
    shapes and never inspects ciphertext. Server-side search is retired:
    the V11 tsvector column stays (forward-only migrations) but inserts stop
    populating it for encrypted rows and `Messages.search` is removed —
    search moves client-side (Phase 15's conversation-list search filters the
    local cache).
  - **App**: libsignal store over `SecureCredentialStore` + local persistence;
    encrypt/decrypt wired into the repository send/receive seams; safety
    number in contact detail + identity-change banner; client-side search.
  - **Tests**: core envelope/safety-number unit tests, server key-directory
    integration test, app engine tests (libsignal JVM artifact
    `org.signal:libsignal-client` as `testImplementation` for a genuine X3DH +
    Double Ratchet round-trip on the host JVM), oracle `verify-e2ee.mjs`,
    prototype G11 (two users exchange prekeys and an envelope over real HTTP).
- **Phase 23 — E2EE for media**: AES-256-GCM per-attachment content key
  generated on-device, key travels inside each recipient's envelope;
  thumbnails generated on-device at attach time (long edge 256px, same cap as
  the old server path) and encrypted like any attachment. The server stores
  opaque bytes and its decode/thumbnail path is retired for encrypted media.
- **Docs**: ADR-0015 (this file), protocol.md key-directory + envelope
  contract, feature-plan Phases 22–23, CHANGELOG, README security section,
  operations.md (key-directory sizing, prekey notes).

### What is deliberately NOT encrypted (and why, honestly)

- **Reaction emojis** (V6): the picker offers a fixed, public set of emojis —
  ciphertext over a value from a tiny known set is trivially brute-forced, so
  encryption would add complexity with zero confidentiality gain. Metadata
  (who reacted, when) is exposed exactly like in Signal.
- **Reply references and deletion tombstones** (V7/V8): message-id metadata,
  no content to protect.
- **Call media**: PBX-relayed, see decision 7.


