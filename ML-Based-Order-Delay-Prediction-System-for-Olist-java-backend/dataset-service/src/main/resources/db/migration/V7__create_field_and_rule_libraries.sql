CREATE TABLE field_definitions (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    display_name VARCHAR(255),
    data_type VARCHAR(32) NOT NULL,
    semantic_role VARCHAR(64),
    source_table VARCHAR(255),
    description VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_field_definitions PRIMARY KEY (id),
    CONSTRAINT uk_field_definitions_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_field_definitions_owner_status ON field_definitions (owner_id, status);
CREATE INDEX idx_field_definitions_owner_role ON field_definitions (owner_id, semantic_role);
CREATE INDEX idx_field_definitions_updated_at ON field_definitions (updated_at DESC);

CREATE TABLE cleaning_rules (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    target_field VARCHAR(128),
    severity VARCHAR(16) NOT NULL,
    rule_expression VARCHAR(2000),
    description VARCHAR(1000),
    enabled BOOLEAN NOT NULL,
    priority INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_cleaning_rules PRIMARY KEY (id),
    CONSTRAINT uk_cleaning_rules_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_cleaning_rules_owner_type ON cleaning_rules (owner_id, rule_type);
CREATE INDEX idx_cleaning_rules_owner_enabled ON cleaning_rules (owner_id, enabled);
CREATE INDEX idx_cleaning_rules_updated_at ON cleaning_rules (updated_at DESC);
