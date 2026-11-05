CREATE TABLE workflow_runs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_revision BIGINT NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    output_json MEDIUMTEXT NULL,
    error_message VARCHAR(2000) NULL,
    retry_of_id VARCHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_workflow_runs_owner_created ON workflow_runs(owner_id, created_at);
CREATE INDEX idx_workflow_runs_workflow_created ON workflow_runs(workflow_id, created_at);

CREATE TABLE workflow_run_nodes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_json MEDIUMTEXT NULL,
    error_message VARCHAR(2000) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_workflow_run_nodes_run FOREIGN KEY (run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE,
    CONSTRAINT uq_workflow_run_nodes_id UNIQUE (run_id, node_id)
);

CREATE INDEX idx_workflow_run_nodes_run ON workflow_run_nodes(run_id, created_at);
