-- Preserve the exact source event for official QQ passive replies and per-message sequences.
ALTER TABLE outbox ADD COLUMN reply_to_message_id text REFERENCES group_message(id) ON DELETE SET NULL;
