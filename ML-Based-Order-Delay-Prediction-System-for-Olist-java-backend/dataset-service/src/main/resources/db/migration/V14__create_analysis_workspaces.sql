CREATE TABLE analysis_workspaces (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    cards_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_analysis_workspaces PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_analysis_workspaces_owner_name ON analysis_workspaces (owner_id, name);
CREATE INDEX idx_analysis_workspaces_owner_updated ON analysis_workspaces (owner_id, updated_at DESC);
