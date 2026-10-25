ALTER TABLE dataset_versions ADD COLUMN file_count INT NOT NULL DEFAULT 0;

CREATE TABLE dataset_files (
    id CHAR(36) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    content_type VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_dataset_files PRIMARY KEY (id),
    CONSTRAINT fk_dataset_files_dataset FOREIGN KEY (dataset_id)
        REFERENCES dataset_versions (id) ON DELETE CASCADE
);

CREATE INDEX idx_dataset_files_dataset_id ON dataset_files (dataset_id);

ALTER TABLE dataset_tables ADD COLUMN file_id CHAR(36);
ALTER TABLE dataset_tables ADD COLUMN file_name VARCHAR(512);
ALTER TABLE dataset_tables ADD CONSTRAINT fk_dataset_tables_file FOREIGN KEY (file_id)
    REFERENCES dataset_files (id) ON DELETE CASCADE;

CREATE INDEX idx_dataset_tables_file_id ON dataset_tables (file_id);