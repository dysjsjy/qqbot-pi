-- Local SQLite schema: timestamps are UTC epoch milliseconds; vectors are JSON text.
CREATE TABLE group_config (
 id text NOT NULL PRIMARY KEY, channel varchar(16) NOT NULL CHECK(channel IN ('QQ','WEIXIN')),
 account_id varchar(160) NOT NULL, external_id varchar(160) NOT NULL, name varchar(200) NOT NULL,
 enabled integer NOT NULL DEFAULT false, reply_enabled integer NOT NULL DEFAULT true,
 admin_ids text NOT NULL DEFAULT '', epoch integer NOT NULL DEFAULT 0, revision integer NOT NULL DEFAULT 0,
 created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)), collection_since integer,
 last_received_at integer, connection_state varchar(32) NOT NULL DEFAULT 'UNKNOWN',
 UNIQUE(channel,account_id,external_id)
);
CREATE TABLE group_message (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), message_key varchar(256) NOT NULL,
 sender_id varchar(160) NOT NULL, sender_name varchar(200) NOT NULL, body text NOT NULL,
 occurred_at integer NOT NULL, received_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 epoch integer NOT NULL, kind varchar(32) NOT NULL, index_state varchar(24) NOT NULL DEFAULT 'PENDING',
 index_attempts integer NOT NULL DEFAULT 0, error_code varchar(80), UNIQUE(group_id,message_key)
);
CREATE INDEX message_window ON group_message(group_id,epoch,occurred_at,id);
CREATE INDEX message_index_queue ON group_message(index_state,received_at);
CREATE TABLE conversation (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), sender_id varchar(160) NOT NULL,
 generation integer NOT NULL DEFAULT 0, checkpoint_ref text, updated_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)), UNIQUE(group_id,sender_id)
);
CREATE TABLE agent_run (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), message_id text UNIQUE REFERENCES group_message(id) ON DELETE SET NULL,
 conversation_id text NOT NULL REFERENCES conversation(id), generation integer NOT NULL, epoch integer NOT NULL,
 kind varchar(20) NOT NULL, prompt text NOT NULL, state varchar(32) NOT NULL DEFAULT 'QUEUED',
 final_text text, error_code varchar(80), created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)), finished_at integer
);
CREATE INDEX agent_queue ON agent_run(state,created_at);
CREATE TABLE memory_chunk (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), message_id text NOT NULL REFERENCES group_message(id) ON DELETE CASCADE,
 epoch integer NOT NULL, part_index integer NOT NULL, body text NOT NULL, embedding text NOT NULL,
 model varchar(200) NOT NULL, dimensions integer NOT NULL, created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 UNIQUE(message_id,part_index,model)
);
CREATE INDEX memory_scope ON memory_chunk(group_id,epoch);
CREATE TABLE daily_schedule (
 group_id text NOT NULL PRIMARY KEY REFERENCES group_config(id), enabled integer NOT NULL DEFAULT false,
 local_time text NOT NULL DEFAULT '09:00', zone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
 revision integer NOT NULL DEFAULT 0, next_run_at integer NOT NULL
);
CREATE TABLE daily_run (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), business_date text NOT NULL,
 epoch integer NOT NULL, trigger_type varchar(16) NOT NULL, state varchar(32) NOT NULL DEFAULT 'QUEUED',
 attempt integer NOT NULL DEFAULT 1, source_ids text, snapshot_at integer, coverage text,
 report text, error_code varchar(80), created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)), finished_at integer,
 UNIQUE(group_id,business_date)
);
CREATE TABLE outbox (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), result_key varchar(160) NOT NULL,
 agent_run_id text REFERENCES agent_run(id), daily_run_id text REFERENCES daily_run(id),
 conversation_id text REFERENCES conversation(id), generation integer, epoch integer NOT NULL,
 part_index integer NOT NULL, body text NOT NULL, state varchar(24) NOT NULL DEFAULT 'PENDING',
 attempts integer NOT NULL DEFAULT 0, next_attempt_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 platform_id varchar(256), error_code varchar(80), created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 UNIQUE(result_key,part_index)
);
CREATE TABLE collection_gap (
 id text NOT NULL PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id), starts_at integer NOT NULL,
 ends_at integer, reason varchar(100) NOT NULL
);
CREATE TABLE admin_audit (
 id text NOT NULL PRIMARY KEY, action varchar(80) NOT NULL, target varchar(200) NOT NULL,
 created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer))
);
CREATE TABLE app_metadata (name varchar(100) PRIMARY KEY, value text NOT NULL);
