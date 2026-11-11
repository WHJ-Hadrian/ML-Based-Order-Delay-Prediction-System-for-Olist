CREATE TABLE training_runs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    dataset_id VARCHAR(36) NOT NULL,
    dataset_version_id VARCHAR(36) NOT NULL,
    table_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NULL,
    task VARCHAR(32) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    target_column VARCHAR(255) NOT NULL,
    feature_columns_json TEXT NOT NULL,
    test_size DOUBLE NOT NULL,
    random_state INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255) NULL,
    request_json TEXT NOT NULL,
    result_json TEXT NULL,
    model_version_id VARCHAR(36) NULL,
    artifact_id VARCHAR(255) NULL,
    error_message TEXT NULL,
    attempts INT NOT NULL DEFAULT 0,
    claim_token VARCHAR(64) NULL,
    lease_expires_at TIMESTAMP(6) NULL,
    owner_token TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_training_runs_owner_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_training_runs_owner_created ON training_runs(owner_id, created_at);
CREATE INDEX idx_training_runs_status_created ON training_runs(status, created_at);
CREATE INDEX idx_training_runs_status_lease ON training_runs(status, lease_expires_at);
