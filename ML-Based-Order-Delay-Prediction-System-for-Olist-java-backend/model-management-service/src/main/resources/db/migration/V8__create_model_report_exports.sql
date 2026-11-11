CREATE TABLE model_report_exports (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    model_id VARCHAR(36) NOT NULL,
    artifact_id VARCHAR(36) NOT NULL,
    format VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    sections_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_model_reports_owner_model_created
    ON model_report_exports (owner_id, model_id, created_at);
