-- Per-attempt delivery history (P4-B1).
--
-- A message records only its retry count. The admin console shows the history behind it, and
-- jms-consumer throttles an afnemer on how many attempts to its host failed recently -- both of
-- which ebms-core answered from its own delivery_log, through ebms-api-service. Same table name
-- as there, but status is stored as text, like messages.status: ebms-core's numbering (1
-- succeeded, 2 failed, 3 expired) is a wire format for the console, applied at the API edge.
--
-- Keyed on the ebMS message id rather than a foreign key to messages.id: the console asks by
-- message id, and an attempt is history -- it should outlive nothing, but nor should deleting a
-- message be blocked by it.
CREATE TABLE delivery_log (
    id            BIGSERIAL   PRIMARY KEY,
    message_id    TEXT        NOT NULL,
    time_stamp    TIMESTAMPTZ NOT NULL,
    uri           TEXT,
    status        TEXT        NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'EXPIRED')),
    error_message TEXT
);

CREATE INDEX idx_delivery_log_message_id ON delivery_log (message_id);
-- The throttling poll: failed attempts in the last few minutes, every 30 to 60 seconds.
CREATE INDEX idx_delivery_log_failures ON delivery_log (time_stamp) WHERE status = 'FAILED';

-- When a message last changed status (P4-C1). The console lists it as statusTime; ebms-core
-- kept it in ebms_message.status_time. Existing rows take their creation time, which is the
-- last status change for anything never retried.
ALTER TABLE messages ADD COLUMN status_time TIMESTAMPTZ;
UPDATE messages SET status_time = created_at;
ALTER TABLE messages ALTER COLUMN status_time SET NOT NULL;
