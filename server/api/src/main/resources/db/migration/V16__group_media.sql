-- V16: group media (Phase 21, ADR-0012 deferred piece). A media row is now
-- scoped to exactly one conversation: pairwise (peer set, group_id NULL) or
-- group (group_id set, peer NULL). Authorisation for GET /v1/media/{id} then
-- resolves through the same membership check the group REST paths use,
-- instead of the owner/peer pair.
--
-- `peer` becomes nullable because a group media row has no single peer; the
-- pairwise rows and indexes are untouched (NULLs never collide in Postgres
-- unique indexes, and idx_media_conversation simply stops matching group rows).

ALTER TABLE media ADD COLUMN group_id TEXT REFERENCES groups(id) ON DELETE CASCADE;
ALTER TABLE media ALTER COLUMN peer DROP NOT NULL;
ALTER TABLE media ADD CONSTRAINT media_one_conversation
    CHECK ((peer IS NOT NULL AND group_id IS NULL) OR (peer IS NULL AND group_id IS NOT NULL));

CREATE INDEX idx_media_group ON media(group_id);
