-- Voice messages (Phase 11): a VOICE message references audio media and
-- carries its recorded duration and sampled waveform, so the receiving
-- client can render the player without decoding the file first.
ALTER TABLE messages
    ADD COLUMN duration_ms INTEGER,
    ADD COLUMN waveform TEXT;
