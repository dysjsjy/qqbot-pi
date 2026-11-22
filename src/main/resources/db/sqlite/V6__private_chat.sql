ALTER TABLE group_config ADD COLUMN chat_type varchar(16) NOT NULL DEFAULT 'GROUP' CHECK (chat_type IN ('GROUP','PRIVATE'));
ALTER TABLE group_config ADD COLUMN target_id varchar(160);
