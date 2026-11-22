ALTER TABLE group_config ADD COLUMN event_seq integer NOT NULL DEFAULT 0;
ALTER TABLE group_message ADD COLUMN event_seq integer;
ALTER TABLE group_message ADD COLUMN reply_to_key varchar(256);
WITH ordered AS (SELECT id,row_number() OVER (PARTITION BY group_id ORDER BY received_at,id) AS seq FROM group_message)
UPDATE group_message SET event_seq=(SELECT seq FROM ordered WHERE ordered.id=group_message.id);
UPDATE group_config SET event_seq=coalesce((SELECT max(m.event_seq) FROM group_message m WHERE m.group_id=group_config.id),0);
CREATE UNIQUE INDEX message_event_seq ON group_message(group_id,event_seq);
CREATE INDEX message_scope_seq ON group_message(group_id,epoch,event_seq);
ALTER TABLE agent_run ADD COLUMN context_seq integer;
ALTER TABLE agent_run ADD COLUMN context_occurred_at integer;
ALTER TABLE agent_run ADD COLUMN context_stats text;
UPDATE agent_run SET context_seq=(SELECT m.event_seq FROM group_message m WHERE m.id=agent_run.message_id AND m.group_id=agent_run.group_id),
 context_occurred_at=(SELECT m.occurred_at FROM group_message m WHERE m.id=agent_run.message_id AND m.group_id=agent_run.group_id);
UPDATE agent_run SET state='CANCELLED',error_code='SOURCE_MISSING',finished_at=cast(unixepoch('subsec')*1000 as integer) WHERE state='QUEUED' AND context_seq IS NULL;
ALTER TABLE outbox ADD COLUMN event_seq integer;
ALTER TABLE outbox ADD COLUMN delivered_at integer;
CREATE UNIQUE INDEX outbox_event_seq ON outbox(group_id,event_seq) WHERE event_seq IS NOT NULL;
CREATE INDEX outbox_scope_seq ON outbox(group_id,epoch,event_seq);
ALTER TABLE memory_policy ADD COLUMN revision integer NOT NULL DEFAULT 0;
ALTER TABLE memory_policy ADD COLUMN context_mode varchar(16) NOT NULL DEFAULT 'LEGACY' CHECK(context_mode IN ('LEGACY','BUDGETED'));
ALTER TABLE memory_policy ADD COLUMN strategy_version integer NOT NULL DEFAULT 1 CHECK(strategy_version IN (1,2));
ALTER TABLE memory_policy ADD COLUMN summary_trigger_tokens integer NOT NULL DEFAULT 6000 CHECK(summary_trigger_tokens BETWEEN 1024 AND 16000);
ALTER TABLE memory_policy ADD COLUMN summary_cooldown_minutes integer NOT NULL DEFAULT 30 CHECK(summary_cooldown_minutes BETWEEN 5 AND 1440);
ALTER TABLE memory_document ADD COLUMN strategy_version integer NOT NULL DEFAULT 1 CHECK(strategy_version IN (1,2));
CREATE TABLE model_call (
 id text NOT NULL PRIMARY KEY,run_id text NOT NULL,group_id text REFERENCES group_config(id) ON DELETE SET NULL,epoch integer NOT NULL,
 purpose varchar(32) NOT NULL CHECK(purpose IN ('CHAT','MEMORY_QUERY','MEMORY_SUMMARY','DAILY_SUMMARY')),
 provider text NOT NULL,model text NOT NULL,state varchar(24) NOT NULL DEFAULT 'RUNNING',dispatched integer NOT NULL DEFAULT false,
 usage_status varchar(16) NOT NULL DEFAULT 'UNKNOWN',estimated_input_tokens integer NOT NULL CHECK(estimated_input_tokens>=0),
 max_output_tokens integer NOT NULL CHECK(max_output_tokens>=0),input_tokens integer CHECK(input_tokens>=0),output_tokens integer CHECK(output_tokens>=0),
 cache_read_tokens integer CHECK(cache_read_tokens>=0),cache_write_tokens integer CHECK(cache_write_tokens>=0),reasoning_tokens integer CHECK(reasoning_tokens>=0),
 reported_cost text,estimated_cost text,currency varchar(32) NOT NULL,price_version text NOT NULL,price_snapshot text NOT NULL,
 error_code varchar(80),created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),finished_at integer
);
CREATE INDEX model_call_daily ON model_call(group_id,created_at,purpose);
