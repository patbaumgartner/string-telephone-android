-- V2: auth_attempts backs the shared fixed-window rate limiter for API tokens.
CREATE TABLE auth_attempts (
  bucket BIGINT NOT NULL,
  key TEXT NOT NULL,
  attempts INTEGER NOT NULL,
  PRIMARY KEY (bucket, key)
);

-- Expired windows are pruned opportunistically; this index keeps that cheap.
CREATE INDEX idx_auth_attempts_bucket ON auth_attempts(bucket);
