ALTER TABLE model_versions ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE model_versions ADD COLUMN approved_at TIMESTAMP(6) NULL;
ALTER TABLE model_versions ADD COLUMN approved_by VARCHAR(255) NULL;
ALTER TABLE model_versions ADD COLUMN deprecated_at TIMESTAMP(6) NULL;
ALTER TABLE model_versions ADD COLUMN model_family VARCHAR(255) NULL;
ALTER TABLE model_versions ADD COLUMN version_no INT NOT NULL DEFAULT 1;

CREATE INDEX idx_model_versions_owner_status ON model_versions(owner_id, status);
