CREATE TABLE dataset_versions (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    content_type VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_dataset_versions PRIMARY KEY (id)
);

CREATE INDEX idx_dataset_versions_status ON dataset_versions (status);
CREATE INDEX idx_dataset_versions_created_at ON dataset_versions (created_at DESC);