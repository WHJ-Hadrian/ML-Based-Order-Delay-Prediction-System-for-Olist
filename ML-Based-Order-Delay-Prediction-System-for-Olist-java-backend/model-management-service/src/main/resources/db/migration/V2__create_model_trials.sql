CREATE TABLE model_trials (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    model_id VARCHAR(36) NOT NULL,
    dataset_id VARCHAR(36) NOT NULL,
    dataset_version_id VARCHAR(36) NOT NULL,
    dataset_version_no INT NOT NULL,
    table_id VARCHAR(36) NOT NULL,
    target_column VARCHAR(255) NULL,
    predicted_rows INT NOT NULL,
    evaluated_rows INT NOT NULL,
    ignored_target_rows INT NOT NULL,
    metrics_json TEXT NOT NULL,
    confusion_matrix_json TEXT NULL,
    evaluation_samples_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_model_trials_model FOREIGN KEY (model_id) REFERENCES model_versions(id)
);

CREATE INDEX idx_model_trials_owner_created ON model_trials(owner_id, created_at);
CREATE INDEX idx_model_trials_model_created ON model_trials(model_id, created_at);
