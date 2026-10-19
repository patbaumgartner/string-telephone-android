# ADR-0009: S3-compatible object storage for media

## Status

Accepted. The prototype stack targets **Garage** as the default object store;
the `MediaStore` interface stays provider-neutral. Supersedes MinIO as the
assumed self-hosted reference (MinIO's community edition was archived in
2026).

## Context

Messaging is text-only today (8 192 UTF-8 bytes per message, see
`docs/protocol.md`). Feature work adds image sharing, which requires storing
binary blobs and serving them to both conversation participants. Constraints
and options:

1. **Files on the API container filesystem.** Simplest, but images are lost on
   container replacement, do not survive multi-replica deployments, and mix
   mutable state into an immutable image — the same anti-pattern ADR-0007
   rejects for PBX configuration.
2. **Blobs in the database.** Keeps one datastore, but multi-megabyte rows
   bloat backups, inflate memory during streaming, and make cursor pagination
   over `messages` slower. PostgreSQL TOAST mitigates but does not solve the
   operational cost.
3. **S3-compatible object store.** Durable, streams well, cheap, and the
   de-facto standard interface. Self-hosted options (Garage, RustFS,
   SeaweedFS) and hosted ones (AWS S3, Cloudflare R2, Backblaze B2) all speak
   the same API, so the choice stays an operator decision.

The project's self-hosted rule (ADR-0003) forbids a hard dependency on a
specific commercial cloud. The storage interface must therefore target the
S3 API, not AWS-specific features.

MinIO was the original candidate, but its community edition was archived in
2026 and is no longer a maintained open-source option. Candidate
self-hosted successors evaluated: **Garage** (Rust, AGPLv3, actively
maintained, designed for self-hosting, community consensus successor),
RustFS (Apache 2.0, reached 1.0 GA but a young project), and SeaweedFS
(mature but heavier).

## Decision

Store media in an **S3-compatible object store** behind a small `MediaStore`
interface owned by the companion API.

- **Interface first.** The API depends on a narrow `MediaStore` seam (put,
  get, delete, presign). The first implementation uses the AWS S3 SDK v2,
  which works against Garage, RustFS, SeaweedFS, R2, B2 and AWS alike via a
  configurable endpoint. A local-filesystem implementation may exist for
  development and tests, but production deployments use S3-compatible storage.
- **Prototype default: Garage.** The compose stack ships a single-node Garage
  container. Production deployments may point the same `MediaStore`
  configuration at any S3-compatible endpoint.
- **Metadata in PostgreSQL.** A `media` table (ADR-0008) records `id`, owner,
  conversation peer, content type, byte size, SHA-256, state (`uploading`,
  `ready`, `expired`) and timestamps. The blob lives only in object storage.
- **Upload path.** The app uploads via `POST /v1/media` (multipart or
  presigned PUT). The server enforces authentication, per-type size limits,
  and content-type allow-listing before the blob is accepted.
- **Download path.** `GET /v1/media/{id}` authenticates with the user token,
  authorises that the requester is a participant of the conversation the media
  belongs to, then streams from the store. Direct presigned URLs to the object
  store are avoided in the first cut so authorisation and audit stay
  server-side.
- **Images.** The server stores the original plus a size-capped thumbnail
  (generated server-side with Java ImageIO at upload; no native media
  dependencies); both are objects under the same media ID.
- **Limits.** Images up to 16 MB, audio up to 50 MB, enforced server-side per
  content type with an allow-list. Uploads exceeding the limit are rejected
  before the blob is accepted.
- **Lifecycle.** Retention is **indefinite**: media attached to a message is
  never deleted automatically (consistent with ADR-0007's "never delete
  retained data automatically"). Orphaned uploads (never attached to a
  message) may be reaped by a future periodic job; that policy decision is
  deferred until it matters. Nothing is deleted automatically today.
- **Never expose object-store credentials to clients**, and never grant the
  API's store credentials write access beyond its bucket prefix.

## Consequences

- The deployment gains one more service (Garage in the prototype compose
  stack) or requires operator-provided object-store credentials.
- Garage is younger than the interface it serves: its S3 implementation is
  the compatibility surface we pin to, and the pinned version is part of the
  compose review. The `MediaStore` seam keeps a later store swap a
  configuration change, not a rewrite.
- Backup strategy now covers two systems: PostgreSQL metadata and object
  storage contents. `docs/operations.md` must document restoring both
  consistently (media without metadata is garbage; metadata without media is a
  broken message).
- Content-type and size allow-lists are security-relevant: uploaded media is
  served back to authenticated peers, so the API must never reflect an
  attacker-controlled content type that could turn into active content.
- The GPL/AGPL boundary is unaffected: the app uploads and downloads over the
  documented network API; no licensing change. Garage itself is AGPLv3 but is
  a separate process reached over the network, so it does not link into the
  AGPL API any more than Asterisk does.
- Tests cover the `MediaStore` seam with an in-memory fake; the S3
  implementation is exercised in integration tests with Testcontainers
  Garage.
