ALTER TABLE messages ADD COLUMN processed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_messages_unprocessed ON messages (direction) WHERE processed = FALSE AND direction = 'INBOUND';
