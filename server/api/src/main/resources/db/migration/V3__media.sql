-- V3: media metadata (ADR-0009). Blobs live in object storage; this table
-- records ownership and authorisation for GET /v1/media/{id}.
CREATE TABLE media (
  id VARCHAR(64) PRIMARY KEY,
  owner VARCHAR(64) NOT NULL REFERENCES users(username),
  peer VARCHAR(64) NOT NULL REFERENCES users(username),
  content_type VARCHAR(255) NOT NULL,
  byte_size BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ready',
  thumbnail_key VARCHAR(128),
  created_at BIGINT NOT NULL
);

CREATE INDEX idx_media_owner ON media(owner);
CREATE INDEX idx_media_peer ON media(peer);
CREATE INDEX idx_media_conversation ON media(owner, peer);
