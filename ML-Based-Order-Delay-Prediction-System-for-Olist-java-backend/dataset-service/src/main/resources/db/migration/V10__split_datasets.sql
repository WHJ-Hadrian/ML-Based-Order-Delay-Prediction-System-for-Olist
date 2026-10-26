-- V10: split logical datasets from immutable versions, and prepare ClickHouse metadata.
-- V8/V9 are frozen. This migration assumes V9 is the starting schema and is not conditional.

CREATE TABLE datasets (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    current_version_id CHAR(36),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    CONSTRAINT pk_datasets PRIMARY KEY (id),
    CONSTRAINT fk_datasets_current_version FOREIGN KEY (current_version_id)
        REFERENCES dataset_versions (id) ON DELETE SET NULL
);

CREATE INDEX idx_datasets_owner_created ON datasets (owner_id, created_at DESC);
CREATE INDEX idx_datasets_owner_deleted ON datasets (owner_id, deleted_at);

INSERT INTO datasets (id, owner_id, name, description, current_version_id, created_at, updated_at)
SELECT id, owner_id, name, description, id, created_at, updated_at
FROM dataset_versions;

ALTER TABLE dataset_versions ADD COLUMN dataset_id CHAR(36);
UPDATE dataset_versions SET dataset_id = id;
ALTER TABLE dataset_versions MODIFY COLUMN dataset_id CHAR(36) NOT NULL;
ALTER TABLE dataset_versions ADD CONSTRAINT fk_dataset_versions_dataset FOREIGN KEY (dataset_id)
    REFERENCES datasets (id) ON DELETE CASCADE;

ALTER TABLE dataset_versions ADD COLUMN version_no INT;
UPDATE dataset_versions SET version_no = 1 WHERE version_no IS NULL;
ALTER TABLE dataset_versions MODIFY COLUMN version_no INT NOT NULL;

ALTER TABLE dataset_versions ADD COLUMN parent_version_id CHAR(36);
ALTER TABLE dataset_versions ADD COLUMN manifest_hash VARCHAR(64);
ALTER TABLE dataset_versions ADD COLUMN schema_hash VARCHAR(64);
ALTER TABLE dataset_versions ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'UPLOAD';
ALTER TABLE dataset_versions ADD COLUMN source_run_id CHAR(36);
ALTER TABLE dataset_versions ADD COLUMN source_workbook_version_id CHAR(36);
ALTER TABLE dataset_versions ADD COLUMN source_node_id VARCHAR(255);
ALTER TABLE dataset_versions ADD COLUMN ready_at DATETIME(6);
ALTER TABLE dataset_versions ADD COLUMN archived_at DATETIME(6);
ALTER TABLE dataset_versions ADD COLUMN deleted_at DATETIME(6);

ALTER TABLE dataset_versions ADD CONSTRAINT fk_dataset_versions_parent FOREIGN KEY (parent_version_id)
    REFERENCES dataset_versions (id);
CREATE UNIQUE INDEX uk_dataset_versions_dataset_version ON dataset_versions (dataset_id, version_no);
CREATE INDEX idx_dataset_versions_dataset_created ON dataset_versions (dataset_id, created_at DESC);
CREATE INDEX idx_dataset_versions_dataset_status ON dataset_versions (dataset_id, status);

ALTER TABLE dataset_tables ADD COLUMN table_key VARCHAR(512);
UPDATE dataset_tables SET table_key = id WHERE table_key IS NULL;
ALTER TABLE dataset_tables MODIFY COLUMN table_key VARCHAR(512) NOT NULL;
ALTER TABLE dataset_tables ADD COLUMN physical_table VARCHAR(255);
CREATE UNIQUE INDEX uk_dataset_tables_dataset_table_key ON dataset_tables (dataset_id, table_key);
CREATE INDEX idx_dataset_tables_dataset_physical ON dataset_tables (dataset_id, physical_table);

ALTER TABLE dataset_columns RENAME COLUMN dataset_id TO dataset_version_id;
ALTER TABLE dataset_columns ADD COLUMN physical_name VARCHAR(255);
ALTER TABLE dataset_columns ADD COLUMN logical_type VARCHAR(32);
ALTER TABLE dataset_columns ADD COLUMN clickhouse_type VARCHAR(128);
ALTER TABLE dataset_columns ADD COLUMN nullable BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE dataset_columns ADD COLUMN file_id CHAR(36);
UPDATE dataset_columns SET logical_type = data_type WHERE logical_type IS NULL;
ALTER TABLE dataset_columns ADD CONSTRAINT fk_dataset_columns_file FOREIGN KEY (file_id)
    REFERENCES dataset_files (id) ON DELETE CASCADE;
CREATE INDEX idx_dataset_columns_version_table_ordinal
    ON dataset_columns (dataset_version_id, table_id, ordinal);

ALTER TABLE dataset_field_bindings RENAME COLUMN dataset_id TO dataset_version_id;
CREATE UNIQUE INDEX uk_dataset_field_bindings_field_version
    ON dataset_field_bindings (field_id, dataset_version_id);
ALTER TABLE dataset_field_bindings DROP INDEX uk_dataset_field_bindings_field;
ALTER TABLE dataset_field_bindings ADD COLUMN binding_status VARCHAR(32) NOT NULL DEFAULT 'BOUND';
ALTER TABLE dataset_field_bindings MODIFY COLUMN table_id CHAR(36) NULL;
ALTER TABLE dataset_field_bindings MODIFY COLUMN column_id CHAR(36) NULL;
CREATE INDEX idx_dataset_field_bindings_version_field
    ON dataset_field_bindings (dataset_version_id, field_id);

CREATE TABLE storage_blobs (
    sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_type VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_storage_blobs PRIMARY KEY (sha256)
);

ALTER TABLE dataset_files ADD COLUMN blob_sha256 CHAR(64);
INSERT INTO storage_blobs (sha256, storage_key, size_bytes, content_type, created_at)
SELECT sha256, MIN(storage_key), MAX(size_bytes), MAX(content_type), MIN(created_at)
FROM dataset_files
WHERE sha256 IS NOT NULL AND sha256 <> ''
GROUP BY sha256;
UPDATE dataset_files SET blob_sha256 = sha256 WHERE sha256 IS NOT NULL AND sha256 <> '';
ALTER TABLE dataset_files ADD CONSTRAINT fk_dataset_files_blob FOREIGN KEY (blob_sha256)
    REFERENCES storage_blobs (sha256);
CREATE INDEX idx_dataset_files_blob_sha256 ON dataset_files (blob_sha256);
