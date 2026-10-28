-- V10: last-seen (Phase 13). Epoch millisecond at which the user's last
-- realtime session disconnected (or the account was disabled/signed out);
-- NULL when they have never connected or are currently online. Minute
-- granularity is applied at write time so the value carries no more
-- precision than the UI shows.
ALTER TABLE users ADD COLUMN last_seen_at BIGINT;
