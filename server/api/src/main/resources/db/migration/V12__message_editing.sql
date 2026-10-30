-- V12: message editing (Phase 17). Epoch millisecond at which the author
-- last re-stored the content via `PUT /v1/conversations/{peer}/messages/{id}`;
-- NULL while the message was never edited. Tombstones keep the column but it
-- is ignored after deletion (deletes take precedence, ADR-0011 pattern).
ALTER TABLE messages ADD COLUMN edited_at BIGINT;
