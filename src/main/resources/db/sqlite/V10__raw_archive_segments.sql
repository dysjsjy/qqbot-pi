ALTER TABLE memory_document ADD COLUMN storage TEXT NOT NULL DEFAULT 'MARKDOWN' CHECK(storage IN ('MARKDOWN','SEGMENT_V1'));
CREATE TABLE raw_archive_state (
 group_id TEXT NOT NULL REFERENCES group_config(id) ON DELETE CASCADE, epoch INTEGER NOT NULL,
 last_scanned_seq INTEGER NOT NULL DEFAULT 0, expired_before_archive INTEGER NOT NULL DEFAULT 0,
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','PAUSED','NEEDS_MIGRATION','FAILED')),
 error_code TEXT, attempts INTEGER NOT NULL DEFAULT 0, next_retry_ms INTEGER, updated_ms INTEGER NOT NULL,
 PRIMARY KEY(group_id,epoch)
);
CREATE TABLE raw_archive_segment (
 id TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES group_config(id) ON DELETE CASCADE,
 epoch INTEGER NOT NULL, base_seq INTEGER NOT NULL, last_seq INTEGER NOT NULL,
 generation INTEGER NOT NULL DEFAULT 0 CHECK(generation>=0), committed_bytes INTEGER NOT NULL CHECK(committed_bytes>=64),
 record_count INTEGER NOT NULL CHECK(record_count>0), opened_ms INTEGER NOT NULL,
 min_occurred_ms INTEGER NOT NULL, max_occurred_ms INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('ACTIVE','SEALED','BROKEN')), format_version INTEGER NOT NULL DEFAULT 1 CHECK(format_version=1),
 UNIQUE(group_id,epoch,base_seq), CHECK(base_seq>0 AND last_seq>=base_seq)
);
CREATE UNIQUE INDEX raw_archive_active ON raw_archive_segment(group_id,epoch) WHERE state='ACTIVE';
CREATE INDEX raw_archive_expiry ON raw_archive_segment(state,max_occurred_ms);
CREATE TABLE raw_archive_ref (
 document_id TEXT NOT NULL REFERENCES memory_document(id) ON DELETE CASCADE,
 segment_id TEXT NOT NULL REFERENCES raw_archive_segment(id) ON DELETE CASCADE,
 PRIMARY KEY(document_id,segment_id)
);
CREATE INDEX raw_archive_ref_segment ON raw_archive_ref(segment_id,document_id);
CREATE TABLE raw_archive_gc (
 id TEXT PRIMARY KEY, group_id TEXT NOT NULL, epoch INTEGER NOT NULL,
 operation TEXT NOT NULL CHECK(operation IN ('DELETE_FILES','DELETE_PARTITION','REWRITE_EXPIRED')),
 manifest_json TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','FAILED','DONE')),
 attempts INTEGER NOT NULL DEFAULT 0, next_retry_ms INTEGER, error_code TEXT,
 created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL
);
CREATE INDEX raw_archive_gc_pending ON raw_archive_gc(state,next_retry_ms);
CREATE TABLE memory_file_tombstone (relative_path TEXT PRIMARY KEY, token TEXT NOT NULL);
CREATE TABLE raw_archive_migration (
 id TEXT PRIMARY KEY, group_id TEXT NOT NULL, epoch INTEGER NOT NULL, source_fingerprint TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','PUBLISHED','VERIFIED','FINALIZED','FAILED')),
 manifest_json TEXT NOT NULL, error_code TEXT, updated_ms INTEGER NOT NULL,
 UNIQUE(group_id,epoch,source_fingerprint)
);
