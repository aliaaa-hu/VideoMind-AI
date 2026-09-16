ALTER TABLE media_files
    ADD COLUMN source_url VARCHAR(2048) NULL AFTER file_path,
    ADD COLUMN ingestion_mode VARCHAR(32) NOT NULL DEFAULT 'FULL_VIDEO' AFTER source_url,
    ADD COLUMN source_duration_ms BIGINT NULL AFTER ingestion_mode;
