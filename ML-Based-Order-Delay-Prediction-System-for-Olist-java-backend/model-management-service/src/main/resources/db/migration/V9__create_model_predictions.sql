CREATE TABLE model_predictions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    model_id VARCHAR(36) NOT NULL,
    model_name VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL,
    input_json TEXT NOT NULL,
    result_json TEXT NULL,
    error_message VARCHAR(1000) NULL,
    prediction VARCHAR(64) NULL,
    probability DOUBLE NULL,
    threshold_value DOUBLE NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL
);

CREATE INDEX idx_model_predictions_owner_created
    ON model_predictions (owner_id, created_at);

CREATE INDEX idx_model_predictions_status_created
    ON model_predictions (status, created_at);
