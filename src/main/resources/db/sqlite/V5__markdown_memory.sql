CREATE TABLE memory_policy (
 group_id text PRIMARY KEY REFERENCES group_config(id) ON DELETE CASCADE,
 mode text NOT NULL DEFAULT 'RAW' CHECK(mode IN ('RAW','SUMMARY'))
);
CREATE TABLE memory_document (
 id text PRIMARY KEY, group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 epoch integer NOT NULL, kind text NOT NULL CHECK(kind IN ('RAW','SUMMARY')),
 state text NOT NULL DEFAULT 'QUEUED', source_count integer NOT NULL,
 attempt integer NOT NULL DEFAULT 1, error_code text,
 created_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)), finished_at integer
);
CREATE INDEX memory_document_queue ON memory_document(state,created_at);
CREATE INDEX memory_document_scope ON memory_document(group_id,epoch,kind);
CREATE TABLE memory_document_source (
 document_id text NOT NULL REFERENCES memory_document(id) ON DELETE CASCADE,
 message_id text NOT NULL REFERENCES group_message(id) ON DELETE CASCADE,
 kind text NOT NULL,
 PRIMARY KEY(document_id,message_id), UNIQUE(message_id,kind)
);
