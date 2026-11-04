CREATE TABLE model_versions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    task VARCHAR(32) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    dataset_id VARCHAR(36) NOT NULL,
    dataset_version_id VARCHAR(36) NOT NULL,
    dataset_version_no INT NOT NULL,
    table_id VARCHAR(36) NOT NULL,
    target_column VARCHAR(255) NOT NULL,
    feature_columns_json TEXT NOT NULL,
    feature_types_json TEXT NOT NULL,
    test_size DOUBLE NOT NULL,
    random_state INT NOT NULL,
    metrics_json TEXT NOT NULL,
    train_rows INT NOT NULL,
    test_rows INT NOT NULL,
    dropped_target_rows INT NOT NULL,
    artifact_id VARCHAR(36) NOT NULL UNIQUE,
    confusion_matrix_json TEXT NULL,
    evaluation_samples_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_model_versions_owner_created ON model_versions(owner_id, created_at);
