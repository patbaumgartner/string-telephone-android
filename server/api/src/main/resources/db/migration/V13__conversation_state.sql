-- V13: chat list management (Phase 18). Per-user conversation state so all
-- devices of an account agree on pin/mute/archive. Rows are created lazily
-- on first update; absent defaults are all false.
--
-- Pin: up to 5 conversations sort to the top of the list (order: pinned
-- newest activity first, then unpinned). Mute: notifications are
-- suppressed but unread badges stay. Archive: hidden behind the
-- "Archived" entry; a new incoming message un-archives (WhatsApp
-- behaviour, applied client- and server-side).
CREATE TABLE user_conversation_state (
    username   TEXT    NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    peer       TEXT    NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    pinned     BOOLEAN NOT NULL DEFAULT FALSE,
    muted      BOOLEAN NOT NULL DEFAULT FALSE,
    archived   BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at BIGINT  NOT NULL,
    PRIMARY KEY (username, peer),
    CHECK (username <> peer)
);
