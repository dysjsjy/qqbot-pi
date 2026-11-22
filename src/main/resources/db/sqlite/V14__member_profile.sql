-- [INPUT]: 依赖 V13 群配置、消息和模型账目。
-- [OUTPUT]: 保留模型账目并增加画像策略、卡片、观测、作业和互动边。
-- [POS]: 画像按群与 epoch 隔离；删除群级联删除画像数据。
-- [PROTOCOL]: Update this header on change, then check AGENTS.md
CREATE TABLE model_call_v14 (
 id text NOT NULL PRIMARY KEY,run_id text NOT NULL,group_id text REFERENCES group_config(id) ON DELETE SET NULL,epoch integer NOT NULL,
 purpose varchar(32) NOT NULL CHECK(purpose IN ('CHAT','MEMORY_QUERY','MEMORY_SUMMARY','DAILY_SUMMARY','MEMBER_PROFILE')),
 provider text NOT NULL,model text NOT NULL,state varchar(24) NOT NULL DEFAULT 'RUNNING',dispatched integer NOT NULL DEFAULT false,
 usage_status varchar(16) NOT NULL DEFAULT 'UNKNOWN',estimated_input_tokens integer NOT NULL CHECK(estimated_input_tokens>=0),
 max_output_tokens integer NOT NULL CHECK(max_output_tokens>=0),input_tokens integer CHECK(input_tokens>=0),output_tokens integer CHECK(output_tokens>=0),
 cache_read_tokens integer CHECK(cache_read_tokens>=0),cache_write_tokens integer CHECK(cache_write_tokens>=0),reasoning_tokens integer CHECK(reasoning_tokens>=0),
 reported_cost text,estimated_cost text,currency varchar(32) NOT NULL,price_version text NOT NULL,price_snapshot text NOT NULL,
 error_code varchar(80),created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),finished_at integer
);
INSERT INTO model_call_v14 SELECT * FROM model_call;
DROP TABLE model_call;
ALTER TABLE model_call_v14 RENAME TO model_call;
CREATE INDEX model_call_daily ON model_call(group_id,created_at,purpose);

CREATE TABLE member_profile_policy (
 group_id text NOT NULL PRIMARY KEY REFERENCES group_config(id) ON DELETE CASCADE,
 enabled integer NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),
 inject_enabled integer NOT NULL DEFAULT 0 CHECK(inject_enabled IN (0,1) AND inject_enabled<=enabled),
 max_profiles integer NOT NULL DEFAULT 20 CHECK(max_profiles BETWEEN 5 AND 50),
 min_messages_30d integer NOT NULL DEFAULT 20 CHECK(min_messages_30d BETWEEN 5 AND 200),
 min_active_days_30d integer NOT NULL DEFAULT 3 CHECK(min_active_days_30d BETWEEN 1 AND 30),
 cold_start_lookback_days integer NOT NULL DEFAULT 90 CHECK(cold_start_lookback_days BETWEEN 7 AND 365),
 update_trigger_messages integer NOT NULL DEFAULT 15 CHECK(update_trigger_messages BETWEEN 5 AND 100),
 update_cooldown_hours integer NOT NULL DEFAULT 24 CHECK(update_cooldown_hours BETWEEN 1 AND 168),
 daily_model_budget_calls integer NOT NULL DEFAULT 10 CHECK(daily_model_budget_calls BETWEEN 1 AND 100),
 profile_ttl_days integer NOT NULL DEFAULT 60 CHECK(profile_ttl_days BETWEEN 14 AND 180),
 style_hint_mode text NOT NULL DEFAULT 'SUBTLE' CHECK(style_hint_mode IN ('SUBTLE','WARM')),
 revision integer NOT NULL DEFAULT 0,
 updated_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer))
);

CREATE TABLE member_profile (
 id text NOT NULL PRIMARY KEY,
 group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 epoch integer NOT NULL,
 sender_id varchar(160) NOT NULL,
 display_name varchar(200) NOT NULL,
 aliases_json text NOT NULL DEFAULT '[]',
 l0_stats_json text NOT NULL DEFAULT '{}',
 profile_card text NOT NULL DEFAULT '',
 admin_note text NOT NULL DEFAULT '',
 opted_out integer NOT NULL DEFAULT 0 CHECK(opted_out IN (0,1)),
 locked_by_admin integer NOT NULL DEFAULT 0 CHECK(locked_by_admin IN (0,1)),
 active_slot integer NOT NULL DEFAULT 0 CHECK(active_slot IN (0,1)),
 needs_reflection integer NOT NULL DEFAULT 0 CHECK(needs_reflection IN (0,1)),
 pending_confirmed_changes integer NOT NULL DEFAULT 0 CHECK(pending_confirmed_changes>=0),
 confidence_score real NOT NULL DEFAULT 0.0,
 source_floor_seq integer NOT NULL DEFAULT 0,
 last_profiled_seq integer NOT NULL DEFAULT 0,
 replay_through_seq integer,
 last_profiled_at integer,
 last_reflected_at integer,
 last_active_at integer,
 version integer NOT NULL DEFAULT 0,
 created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 updated_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 UNIQUE(group_id,epoch,sender_id)
);
CREATE INDEX member_profile_scope ON member_profile(group_id,epoch,opted_out,last_active_at DESC);

CREATE TABLE member_profile_observation (
 id text NOT NULL PRIMARY KEY,
 profile_id text NOT NULL REFERENCES member_profile(id) ON DELETE CASCADE,
 group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 epoch integer NOT NULL,
 category varchar(32) NOT NULL CHECK(category IN ('IDENTITY_ROLE','INTEREST_TOPIC','EXPERTISE','SPEAKING_STYLE','SOCIAL_PREF','RUNNING_GAG')),
 statement text NOT NULL,
 confidence real NOT NULL CHECK(confidence>=0.0 AND confidence<=1.0),
 evidence_count integer NOT NULL DEFAULT 1 CHECK(evidence_count>=1),
 distinct_days integer NOT NULL DEFAULT 1 CHECK(distinct_days>=1),
 state varchar(24) NOT NULL CHECK(state IN ('CANDIDATE','CONFIRMED','SUPERSEDED','EXPIRED','DELETED')),
 supersedes_id text,
 source_message_ids_json text NOT NULL DEFAULT '[]',
 source_days_json text NOT NULL DEFAULT '[]',
 source_windows_json text NOT NULL DEFAULT '[]',
 source_expired_count integer NOT NULL DEFAULT 0 CHECK(source_expired_count>=0),
 first_seen_at integer NOT NULL,
 last_reinforced_at integer NOT NULL,
 expires_at integer NOT NULL,
 created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 updated_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer))
);
CREATE INDEX member_obs_lookup ON member_profile_observation(profile_id,state,last_reinforced_at DESC);
CREATE INDEX member_obs_expiry ON member_profile_observation(state,expires_at);

CREATE TABLE member_profile_job (
 id text NOT NULL PRIMARY KEY,
 group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 epoch integer NOT NULL,
 profile_id text REFERENCES member_profile(id) ON DELETE CASCADE,
 sender_id varchar(160) NOT NULL,
 job_type varchar(16) NOT NULL CHECK(job_type IN ('EXTRACT','REFLECT','REPLAY')),
 trigger_type varchar(16) NOT NULL CHECK(trigger_type IN ('AUTO','MANUAL','RETRY')),
 state varchar(24) NOT NULL CHECK(state IN ('QUEUED','RUNNING','READY','FAILED','CANCELLED')),
 attempt integer NOT NULL DEFAULT 1 CHECK(attempt BETWEEN 1 AND 3),
 from_seq integer,
 to_seq integer,
 source_count integer NOT NULL DEFAULT 0,
 error_code varchar(80),
 created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 finished_at integer
);
CREATE INDEX member_profile_job_queue ON member_profile_job(state,created_at);
CREATE INDEX member_profile_job_scope ON member_profile_job(group_id,epoch,created_at DESC);
CREATE UNIQUE INDEX member_profile_job_active ON member_profile_job(profile_id) WHERE state IN ('QUEUED','RUNNING');

CREATE TABLE group_social_edge (
 group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 epoch integer NOT NULL,
 from_sender_id varchar(160) NOT NULL,
 to_sender_id varchar(160) NOT NULL,
 reply_count_30d integer NOT NULL DEFAULT 0,
 mention_count_30d integer NOT NULL DEFAULT 0,
 last_interaction_at integer NOT NULL,
 PRIMARY KEY(group_id,epoch,from_sender_id,to_sender_id)
);
CREATE INDEX group_social_edge_top ON group_social_edge(group_id,epoch,from_sender_id,last_interaction_at DESC);
