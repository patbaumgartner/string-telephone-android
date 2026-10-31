-- V14: group chats (Phase 19, ADR-0012). Groups extend the pairwise model:
-- membership lives in its own table (never overload `recipient`), message
-- authorship stays derived from the principal, and per-member read state
-- follows the Phase 18 per-user precedent.
--
-- A message is either pairwise (sender + recipient) or group level
-- (group_id set, recipient empty in the row). The insert path binds the
-- right shape per kind; the partial unique indexes below keep idempotency
-- scoped per conversation kind instead of overloading the pairwise triple.

CREATE TABLE groups (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 64),
    creator    TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE group_members (
    group_id  TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    username  TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    role      TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MEMBER')),
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (group_id, username)
);

CREATE INDEX idx_group_members_user ON group_members(username);

ALTER TABLE messages ADD COLUMN group_id TEXT REFERENCES groups(id) ON DELETE CASCADE;
-- The pairwise uniqueness becomes partial: group messages get their own
-- idempotency scope instead of overloading (sender, recipient, client_key).
DROP INDEX idx_messages_pair_time;
CREATE UNIQUE INDEX idx_messages_pair_client_key ON messages(sender, recipient, client_key) WHERE group_id IS NULL;
CREATE UNIQUE INDEX idx_messages_group_client_key ON messages(sender, group_id, client_key) WHERE group_id IS NOT NULL;
CREATE INDEX idx_messages_pair_time ON messages(sender, recipient, created_at) WHERE group_id IS NULL;
CREATE INDEX idx_messages_group_time ON messages(group_id, created_at) WHERE group_id IS NOT NULL;

-- Per-member read state (ADR-0010 extended to N readers). One row per
-- (message, reader); the pairwise messages.read_at column stays for the
-- pairwise path and is retired for group messages.
CREATE TABLE group_message_reads (
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    username   TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    read_at    BIGINT NOT NULL,
    PRIMARY KEY (message_id, username)
);

CREATE INDEX idx_group_message_reads_user ON group_message_reads(username);
