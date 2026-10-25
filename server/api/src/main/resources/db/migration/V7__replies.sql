-- V7: reply/quote (ADR-0011, Phase 9).
-- Adds a nullable foreign key from a message to the message it replies to.
-- The reply must belong to the same conversation (validated server-side).

ALTER TABLE messages ADD COLUMN reply_to TEXT REFERENCES messages(id) ON DELETE SET NULL;

CREATE INDEX idx_messages_reply_to ON messages(reply_to);