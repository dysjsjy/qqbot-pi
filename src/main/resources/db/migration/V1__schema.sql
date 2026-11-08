CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE group_config (
 id uuid PRIMARY KEY, channel varchar(16) NOT NULL CHECK(channel IN ('QQ','WEIXIN')),
 account_id varchar(160) NOT NULL, external_id varchar(160) NOT NULL, name varchar(200) NOT NULL,
 enabled boolean NOT NULL DEFAULT false, reply_enabled boolean NOT NULL DEFAULT true,
 admin_ids text NOT NULL DEFAULT '', epoch bigint NOT NULL DEFAULT 0, revision bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), collection_since timestamptz,
 last_received_at timestamptz, connection_state varchar(32) NOT NULL DEFAULT 'UNKNOWN',
 UNIQUE(channel,account_id,external_id)
);
CREATE TABLE group_message (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), message_key varchar(256) NOT NULL,
 sender_id varchar(160) NOT NULL, sender_name varchar(200) NOT NULL, body text NOT NULL,
 occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
 epoch bigint NOT NULL, kind varchar(32) NOT NULL, index_state varchar(24) NOT NULL DEFAULT 'PENDING',
 index_attempts integer NOT NULL DEFAULT 0, error_code varchar(80), UNIQUE(group_id,message_key)
);
CREATE INDEX message_window ON group_message(group_id,epoch,occurred_at,id);
CREATE INDEX message_index_queue ON group_message(index_state,received_at);
CREATE TABLE conversation (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), sender_id varchar(160) NOT NULL,
 generation bigint NOT NULL DEFAULT 0, checkpoint_ref text, updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(group_id,sender_id)
);
CREATE TABLE agent_run (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), message_id uuid UNIQUE REFERENCES group_message(id) ON DELETE SET NULL,
 conversation_id uuid NOT NULL REFERENCES conversation(id), generation bigint NOT NULL, epoch bigint NOT NULL,
 kind varchar(20) NOT NULL, prompt text NOT NULL, state varchar(32) NOT NULL DEFAULT 'QUEUED',
 final_text text, error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);
CREATE INDEX agent_queue ON agent_run(state,created_at);
CREATE TABLE memory_chunk (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), message_id uuid NOT NULL REFERENCES group_message(id) ON DELETE CASCADE,
 epoch bigint NOT NULL, part_index integer NOT NULL, body text NOT NULL, embedding vector NOT NULL,
 model varchar(200) NOT NULL, dimensions integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(message_id,part_index,model)
);
CREATE INDEX memory_scope ON memory_chunk(group_id,epoch);
CREATE TABLE daily_schedule (
 group_id uuid PRIMARY KEY REFERENCES group_config(id), enabled boolean NOT NULL DEFAULT false,
 local_time time NOT NULL DEFAULT '09:00', zone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
 revision bigint NOT NULL DEFAULT 0, next_run_at timestamptz NOT NULL
);
CREATE TABLE daily_run (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), business_date date NOT NULL,
 epoch bigint NOT NULL, trigger_type varchar(16) NOT NULL, state varchar(32) NOT NULL DEFAULT 'QUEUED',
 attempt integer NOT NULL DEFAULT 1, source_ids jsonb, snapshot_at timestamptz, coverage jsonb,
 report text, error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
 UNIQUE(group_id,business_date)
);
CREATE TABLE outbox (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), result_key varchar(160) NOT NULL,
 agent_run_id uuid REFERENCES agent_run(id), daily_run_id uuid REFERENCES daily_run(id),
 conversation_id uuid REFERENCES conversation(id), generation bigint, epoch bigint NOT NULL,
 part_index integer NOT NULL, body text NOT NULL, state varchar(24) NOT NULL DEFAULT 'PENDING',
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 platform_id varchar(256), error_code varchar(80), created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(result_key,part_index)
);
CREATE TABLE collection_gap (
 id uuid PRIMARY KEY, group_id uuid NOT NULL REFERENCES group_config(id), starts_at timestamptz NOT NULL,
 ends_at timestamptz, reason varchar(100) NOT NULL
);
CREATE TABLE admin_audit (
 id uuid PRIMARY KEY, action varchar(80) NOT NULL, target varchar(200) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app_metadata (name varchar(100) PRIMARY KEY, value text NOT NULL);
