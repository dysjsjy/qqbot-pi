-- Per-group daily summary scene style and member attribution mode.
ALTER TABLE daily_schedule ADD COLUMN style_mode varchar(24) NOT NULL DEFAULT 'CASUAL'
 CHECK(style_mode IN ('CASUAL','KNOWLEDGE','WORK'));
ALTER TABLE daily_schedule ADD COLUMN speaker_mode varchar(24) NOT NULL DEFAULT 'KEY_ONLY'
 CHECK(speaker_mode IN ('KEY_ONLY','ANONYMOUS','ACTIVE'));
