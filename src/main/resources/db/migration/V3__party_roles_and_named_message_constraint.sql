-- Party roles (P1-1).
-- ebMS 2.0 carries eb:Role inside eb:From and eb:To, and jms-consumer sets both from its
-- CPA lookup. Nullable: not every CPA declares roles, and inbound messages may omit them.
ALTER TABLE messages ADD COLUMN from_role TEXT;
ALTER TABLE messages ADD COLUMN to_role   TEXT;

-- Name the message_id uniqueness constraint (P1-3).
--
-- This name is part of the integration contract, not an implementation detail.
-- jms-consumer's JmsMessageConsumer.isDuplicateMessageId() treats a resubmission as a
-- benign JMS redelivery only when the error text contains BOTH
--   'duplicate key value violates unique constraint'  AND  'uc_ebms_message'
-- Postgres would otherwise name the constraint implied by V1's "message_id TEXT UNIQUE"
-- messages_message_id_key, that check would fail, and the consumer's infinite-retry policy
-- would spin forever on a message that can never succeed.
--
-- If you rename this, fix jms-consumer in the same change.
ALTER TABLE messages DROP CONSTRAINT IF EXISTS messages_message_id_key;
ALTER TABLE messages ADD CONSTRAINT uc_ebms_message UNIQUE (message_id);
