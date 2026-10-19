# ADR-0008: PostgreSQL for companion API persistence

## Status

Accepted. Supersedes the SQLite storage choice in ADR-0003 and retained by
ADR-0007 ("Keep SQLite and the existing messaging/idempotency behavior" is
reversed by this decision).

## Context

The companion API currently stores users, messages, PBX account state and PBX
settings in SQLite (`server/api/.../Database.kt`). SQLite was adequate for the
prototype: a single API process, low write concurrency, and one writer at a
time. The planned feature set changes the workload:

- **Read receipts** require per-message read-state updates from two parties,
  not just appends from one sender.
- **Media messaging** adds a `media` table with lifecycle state (uploading,
  ready, expired) that benefits from transactions spanning message inserts.
- Multiple admin and user sessions now write concurrently (account lifecycle,
  PBX reconciliation retries every 15 seconds, message sends, read marks).

SQLite's single-writer model means every write serialises through one lock.
The current code already works around this with bounded-wait connections per
operation (see `Database.kt`), but that approach caps throughput and makes
horizontal scaling impossible. Operational tooling (point-in-time recovery,
replication, monitoring) is also weaker than for a client/server database.

Options considered:

1. **Stay on SQLite (WAL mode).** Zero migration cost, but write contention
   grows with read receipts and media metadata, and a second API replica is
   impossible. Feature work would keep fighting the storage layer.
2. **PostgreSQL.** Mature Kotlin/JDBC support (HikariCP + `postgresql`),
   real transactions and row-level locking, rich indexing, `jsonb` if
   needed later, and standard operational tooling (backups, replication,
   monitoring). Requires a database service in the deployment.
3. **Embedded alternative (H2, DuckDB).** No operational gain over SQLite for
   concurrency; purely a like-for-like swap with little benefit.

## Decision

Use **PostgreSQL 18** (current major with support until 2030) as the
companion API's only datastore.

- Access via HikariCP connection pooling and the PostgreSQL JDBC driver;
  replace the per-operation `DriverManager` connections in `Database` with a
  pooled `DataSource`. The `read`/`tx` seam stays so callers do not change.
- Schema is created and migrated by explicit SQL migrations checked into the
  repository (numbered, forward-only). No ORM is introduced.
- All existing invariants are preserved: server-assigned opaque message IDs,
  authorship derived from the authenticated principal, idempotent sends keyed
  on `(sender, recipient, client_key)`, and cursor pagination on
  `(created_at, id)`.
- Deployment: `docker-compose.yaml` gains a `db` service (`postgres:18-alpine`
  with a named volume) plus a **pgweb** container for database inspection,
  bound to loopback in the development stack only — same exposure policy as
  the plain HTTP API port; never published in production docs. The API
  configures `DB_*` environment variables (replacing `API_DB`).
  SQLite support is removed rather than kept behind a flag — one datastore
  keeps the tested path honest.
- Fresh-installation semantics from ADR-0007 apply: no automatic migration of
  an existing SQLite file and **no import tooling** (decided); operators start
  a new database.

## Consequences

- Concurrent read-receipt writes, message sends and PBX reconciliation no
  longer serialise behind one file lock.
- A second API replica against one database becomes possible at the storage
  layer, though ADR-0007's single-writer rule for PBX configuration still
  forbids running two API writers against one PBX.
- Deployment requires one more service and its credentials; `docs/operations.md`
  and `docs/setup.md` must document backup and upgrade steps.
- Integration tests run against a real PostgreSQL instance (Testcontainers or
  a compose-provided database) so behaviour matches production, not an H2
  emulation.
- The schema-migration directory becomes part of the reviewed change surface:
  protocol changes that touch persistence now ship with a migration.
