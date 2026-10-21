-- Image messaging (Phase 6): a message may carry a media reference instead of
-- (or in addition to) text, and media records remember the decoded dimensions
-- so the client can lay out the bubble before fetching the bytes.
ALTER TABLE messages
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN media_id VARCHAR(64),
    ADD COLUMN width INTEGER,
    ADD COLUMN height INTEGER;

ALTER TABLE media
    ADD COLUMN width INTEGER,
    ADD COLUMN height INTEGER;
