-- V17: end-to-end encrypted messages (ADR-0015, Phase 22).
--
-- The API becomes a ciphertext relay: for an encrypted TEXT message the
-- `content` column is stored empty and the per-recipient ciphertext lives
-- in `message_envelopes`, keyed by the *reader* so each participant (and
-- each of their devices) fetches exactly their own envelope. The key
-- directory tables hold the published X3DH/PQXDH public key material;
-- one-time prekeys are consumed atomically by the sender (DELETE ..
-- RETURNING) so two racing devices can never both use the same prekey.
--
-- `messages.encrypted` distinguishes legacy plaintext rows (readable as
-- before) from encrypted ones; the `search_text` column (V11) stays for
-- forward-only compatibility but is no longer queried — the server cannot
-- full-text search ciphertext, so search moved client-side (Phase 22).
ALTER TABLE messages ADD COLUMN encrypted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE message_envelopes (
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    recipient TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    envelope TEXT NOT NULL,
    PRIMARY KEY (message_id, recipient)
);
CREATE INDEX idx_message_envelopes_recipient ON message_envelopes(recipient);

CREATE TABLE identity_keys (
    username TEXT PRIMARY KEY REFERENCES users(username) ON DELETE CASCADE,
    registration_id INTEGER NOT NULL,
    identity_key TEXT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE signed_prekeys (
    username TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    prekey_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    signature TEXT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (username, prekey_id)
);

CREATE TABLE one_time_prekeys (
    username TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    prekey_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (username, prekey_id)
);

CREATE TABLE kyber_prekeys (
    username TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    prekey_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    signature TEXT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (username, prekey_id)
);
