-- V15: group calls (Phase 20, ADR-0014). One confbridge bridge per call,
-- tracked by the API so lifecycle (create/destroy/join/leave) survives API
-- restarts and so the fan-out frames name the call explicitly.
--
-- The bridge itself lives in Asterisk only; these rows are the API's record
-- of which bridge belongs to which group and who is in it. One active call
-- per group at a time (the bridge is the call).

CREATE TABLE group_calls (
    bridge_id  TEXT PRIMARY KEY,
    group_id   TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    starter    TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    with_video BOOLEAN NOT NULL DEFAULT FALSE,
    started_at BIGINT NOT NULL,
    ended_at   BIGINT
);

CREATE INDEX idx_group_calls_group ON group_calls(group_id, ended_at);

CREATE TABLE group_call_participants (
    bridge_id  TEXT NOT NULL REFERENCES group_calls(bridge_id) ON DELETE CASCADE,
    username   TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    joined_at  BIGINT NOT NULL,
    left_at    BIGINT,
    PRIMARY KEY (bridge_id, username)
);

CREATE INDEX idx_group_call_participants_user ON group_call_participants(username);
