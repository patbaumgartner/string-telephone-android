-- V4: read receipts (ADR-0010). read_at is the epoch millisecond at which the
-- recipient of the message read it in the conversation. NULL means unread.
ALTER TABLE messages ADD COLUMN read_at BIGINT;

-- The read marker updates every unread message of one sender up to a cursor;
-- this index keeps that lookup and the history scan cheap.
CREATE INDEX idx_messages_read ON messages(sender, recipient, read_at);
