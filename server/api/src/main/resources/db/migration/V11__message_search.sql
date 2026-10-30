-- V11: message search (Phase 16). A generated tsvector column over the
-- message content plus a GIN index so `GET /v1/search` stays cheap.
--
-- Config choice (documented in the plan's open decision 4): the `simple`
-- configuration folds case and normalises whitespace but keeps diacritics,
-- so "café" only matches "café". `unaccent` would strip accents, but it is
-- an extension the minimal image may not ship, and this app's user base
-- types with the same keyboard they read with — keeping the letters as
-- typed is the predictable behaviour. Revisit with a migration if that
-- changes.
ALTER TABLE messages ADD COLUMN search_text tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED;

CREATE INDEX idx_messages_search ON messages USING GIN (search_text);
