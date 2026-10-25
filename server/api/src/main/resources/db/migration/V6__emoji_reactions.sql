-- V6: emoji reactions (ADR-0011, Phase 8).
-- One reaction per author per message: the primary key enforces "one per
-- author", and reacting again replaces the emoji row (upsert).

CREATE TABLE reactions (
  message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  author_username TEXT NOT NULL REFERENCES users(username),
  emoji TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (message_id, author_username)
);

CREATE INDEX idx_reactions_message ON reactions(message_id);
