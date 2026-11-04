CREATE TABLE workflow_definitions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    document_json MEDIUMTEXT NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_workflow_definitions_owner_updated ON workflow_definitions(owner_id, updated_at);
