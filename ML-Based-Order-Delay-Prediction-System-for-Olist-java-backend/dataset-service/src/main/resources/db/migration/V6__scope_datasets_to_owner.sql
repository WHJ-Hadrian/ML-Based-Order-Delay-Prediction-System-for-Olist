ALTER TABLE dataset_versions
    ADD COLUMN owner_id VARCHAR(128) NOT NULL DEFAULT 'legacy';

CREATE INDEX idx_dataset_versions_owner_created_at
    ON dataset_versions (owner_id, created_at DESC);
