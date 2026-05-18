CREATE TABLE messages (
    id              UUID PRIMARY KEY,
    message_id      TEXT UNIQUE NOT NULL,
    conversation_id TEXT NOT NULL,
    cpa_id          TEXT NOT NULL,
    from_party_id   TEXT NOT NULL,
    from_party_type TEXT,
    to_party_id     TEXT NOT NULL,
    to_party_type   TEXT,
    service         TEXT NOT NULL,
    action          TEXT NOT NULL,
    direction       TEXT NOT NULL,
    status          TEXT NOT NULL,
    timestamp       TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    retry_count     INT NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,
    ack_requested   BOOLEAN NOT NULL DEFAULT FALSE,
    ref_to_message_id TEXT
);

CREATE INDEX idx_messages_status ON messages (status);
CREATE INDEX idx_messages_direction ON messages (direction);
CREATE INDEX idx_messages_next_retry_at ON messages (next_retry_at) WHERE status = 'PENDING_SEND';

CREATE TABLE payloads (
    id          UUID PRIMARY KEY,
    message_id  UUID NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    content_id  TEXT NOT NULL,
    mime_type   TEXT NOT NULL,
    content     BYTEA NOT NULL
);

CREATE INDEX idx_payloads_message_id ON payloads (message_id);
