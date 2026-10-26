CREATE TABLE rule_workbooks (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    dataset_version_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_rule_workbooks PRIMARY KEY (id),
    CONSTRAINT fk_rule_workbooks_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES dataset_versions (id)
);

CREATE UNIQUE INDEX uk_rule_workbooks_owner_name ON rule_workbooks (owner_id, name);
CREATE INDEX idx_rule_workbooks_owner_updated ON rule_workbooks (owner_id, updated_at DESC);
CREATE INDEX idx_rule_workbooks_dataset ON rule_workbooks (dataset_version_id);

CREATE TABLE rule_workbook_drafts (
    workbook_id CHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    document_json LONGTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_rule_workbook_drafts PRIMARY KEY (workbook_id),
    CONSTRAINT fk_rule_workbook_drafts_workbook FOREIGN KEY (workbook_id)
        REFERENCES rule_workbooks (id) ON DELETE CASCADE
);

CREATE TABLE rule_workbook_versions (
    id CHAR(36) NOT NULL,
    workbook_id CHAR(36) NOT NULL,
    version_number INT NOT NULL,
    dataset_version_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    document_json LONGTEXT NOT NULL,
    compiled_plan_json LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_rule_workbook_versions PRIMARY KEY (id),
    CONSTRAINT fk_rule_workbook_versions_workbook FOREIGN KEY (workbook_id)
        REFERENCES rule_workbooks (id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_workbook_versions_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES dataset_versions (id)
);

CREATE UNIQUE INDEX uk_rule_workbook_versions_number
    ON rule_workbook_versions (workbook_id, version_number);
CREATE INDEX idx_rule_workbook_versions_workbook_created
    ON rule_workbook_versions (workbook_id, created_at DESC);

CREATE TABLE rule_runs (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    workbook_id CHAR(36) NOT NULL,
    workbook_version_id CHAR(36) NOT NULL,
    dataset_version_id CHAR(36) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sample_size INT,
    seed BIGINT,
    error_message VARCHAR(2000),
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    finished_at DATETIME(6),
    CONSTRAINT pk_rule_runs PRIMARY KEY (id),
    CONSTRAINT fk_rule_runs_workbook FOREIGN KEY (workbook_id)
        REFERENCES rule_workbooks (id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_runs_version FOREIGN KEY (workbook_version_id)
        REFERENCES rule_workbook_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_runs_dataset FOREIGN KEY (dataset_version_id)
        REFERENCES dataset_versions (id)
);

CREATE INDEX idx_rule_runs_owner_created ON rule_runs (owner_id, created_at DESC);
CREATE INDEX idx_rule_runs_workbook_created ON rule_runs (workbook_id, created_at DESC);

CREATE TABLE rule_run_nodes (
    id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    node_id VARCHAR(255) NOT NULL,
    node_kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    metrics_json LONGTEXT,
    sample_uri VARCHAR(1024),
    error_message VARCHAR(2000),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    CONSTRAINT pk_rule_run_nodes PRIMARY KEY (id),
    CONSTRAINT fk_rule_run_nodes_run FOREIGN KEY (run_id)
        REFERENCES rule_runs (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_rule_run_nodes_run_node ON rule_run_nodes (run_id, node_id);
