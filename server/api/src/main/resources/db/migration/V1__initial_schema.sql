-- V1: baseline schema, transcribed from the former inline SQLite DDL.
-- Migrations are forward-only: never edit an applied file, add a new V*.sql.

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  display_name TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  api_token_hash TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  revoked_at BIGINT
);

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  sender TEXT NOT NULL,
  recipient TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  client_key TEXT NOT NULL,
  UNIQUE(sender, recipient, client_key)
);

CREATE INDEX idx_messages_pair_time ON messages(sender, recipient, created_at);

CREATE TABLE pbx_accounts (
  username TEXT PRIMARY KEY REFERENCES users(username),
  digest TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  kill_required INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE pbx_settings (id INTEGER PRIMARY KEY CHECK(id=1), settings TEXT NOT NULL);
