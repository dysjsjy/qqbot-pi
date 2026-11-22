-- [INPUT]: 依赖 V11 的群配置、回复策略、消息日志和审计表。
-- [OUTPUT]: 添加群活动视图、榜单快照、命令去重和策略版本。
-- [POS]: 在统一记忆上下文后升级群活动；保留已有群授权和日报配置。
-- [PROTOCOL]: Update this header on change, then check AGENTS.md
ALTER TABLE group_config ADD COLUMN activity_epoch_since integer;
ALTER TABLE reply_policy ADD COLUMN revision integer NOT NULL DEFAULT 0;
ALTER TABLE admin_audit ADD COLUMN details text;

CREATE VIEW group_activity_event AS
SELECT id,group_id,epoch,sender_id,sender_name,occurred_at,received_at,event_seq
FROM group_message
WHERE kind IN ('TEXT','NON_TEXT') AND sender_id<>'';
CREATE INDEX group_activity_sender ON group_message(group_id,epoch,sender_id,occurred_at DESC,event_seq DESC,id DESC)
WHERE kind IN ('TEXT','NON_TEXT') AND sender_id<>'';

CREATE TABLE command_receipt (
 group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 message_key text NOT NULL, expires_at integer NOT NULL,
 PRIMARY KEY(group_id,message_key)
);
CREATE INDEX command_receipt_expiry ON command_receipt(expires_at);

CREATE TABLE activity_schedule (
 group_id text NOT NULL PRIMARY KEY REFERENCES group_config(id) ON DELETE CASCADE,
 enabled integer NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),
 local_time text NOT NULL, top_n integer NOT NULL CHECK(top_n BETWEEN 1 AND 20),
 publish_mode text NOT NULL CHECK(publish_mode IN ('CONSOLE_ONLY','GROUP')),
 revision integer NOT NULL DEFAULT 1, next_run_at integer NOT NULL
);
CREATE INDEX activity_schedule_due ON activity_schedule(enabled,next_run_at);

CREATE TABLE activity_run (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 business_date text NOT NULL, epoch integer NOT NULL, snapshot_at integer NOT NULL,
 starts_at integer NOT NULL, ends_at integer NOT NULL, expires_at integer NOT NULL,
 top_n integer NOT NULL, publish_mode text NOT NULL,
 state text NOT NULL CHECK(state IN ('GENERATED','NO_DATA','CLEARED')),
 delivery_state text NOT NULL CHECK(delivery_state IN ('NOT_REQUESTED','BLOCKED','QUEUED','DELIVERED','FAILED','UNKNOWN','CANCELLED')),
 error_code text, coverage text, ranking text,
 UNIQUE(group_id,business_date)
);
CREATE INDEX activity_run_expiry ON activity_run(expires_at);
