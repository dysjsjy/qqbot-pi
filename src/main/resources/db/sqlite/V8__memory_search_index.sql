CREATE TABLE memory_search_chunk (
 id text NOT NULL PRIMARY KEY,group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,epoch integer NOT NULL,
 source_kind varchar(16) NOT NULL CHECK(source_kind IN ('RAW','MANUAL','SUMMARY')),source_key text NOT NULL,part_index integer NOT NULL,
 body text NOT NULL,content_hash text NOT NULL,source_time integer NOT NULL,indexed_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer)),
 document_id text REFERENCES memory_document(id) ON DELETE CASCADE,UNIQUE(group_id,epoch,source_kind,source_key,part_index)
);
CREATE INDEX search_chunk_scope ON memory_search_chunk(group_id,epoch,source_kind);
CREATE TABLE memory_search_source (
 chunk_id text NOT NULL REFERENCES memory_search_chunk(id) ON DELETE CASCADE,
 message_id text NOT NULL REFERENCES group_message(id) ON DELETE CASCADE,PRIMARY KEY(chunk_id,message_id)
);
CREATE INDEX search_source_message ON memory_search_source(message_id);
CREATE TABLE memory_search_term (
 chunk_id text NOT NULL REFERENCES memory_search_chunk(id) ON DELETE CASCADE,
 group_id text NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,term varchar(128) NOT NULL,PRIMARY KEY(chunk_id,term)
);
CREATE INDEX search_term_lookup ON memory_search_term(group_id,term,chunk_id);
CREATE TABLE memory_search_state (
 group_id text NOT NULL PRIMARY KEY REFERENCES group_config(id) ON DELETE CASCADE,epoch integer NOT NULL,last_message_seq integer NOT NULL DEFAULT 0,
 manual_hash text,updated_at integer NOT NULL DEFAULT (cast(unixepoch('subsec')*1000 as integer))
);
