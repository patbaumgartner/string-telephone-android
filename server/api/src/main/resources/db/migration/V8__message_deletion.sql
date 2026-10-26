-- V8: message deletion (ADR-0011, Phase 10).
-- Soft-delete tombstone for "delete for both sides": content is cleared at
-- the server and the row stays, so sync can never resurrect the text.

ALTER TABLE messages ADD COLUMN deleted_at BIGINT;
ALTER TABLE messages ADD COLUMN deleted_by TEXT;
