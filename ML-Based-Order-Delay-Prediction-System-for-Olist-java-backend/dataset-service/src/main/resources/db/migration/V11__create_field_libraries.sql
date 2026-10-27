CREATE TABLE field_libraries (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    source_type VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_field_libraries PRIMARY KEY (id),
    CONSTRAINT uk_field_libraries_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_field_libraries_owner_updated ON field_libraries (owner_id, updated_at DESC);

ALTER TABLE field_definitions ADD COLUMN library_id CHAR(36);
ALTER TABLE field_definitions ADD COLUMN source_dataset_id CHAR(36);
ALTER TABLE field_definitions ADD COLUMN source_dataset_name VARCHAR(255);
ALTER TABLE field_definitions ADD COLUMN source_table_name VARCHAR(255);
ALTER TABLE field_definitions ADD COLUMN source_column_name VARCHAR(512);

ALTER TABLE field_definitions ADD CONSTRAINT fk_field_definitions_library
    FOREIGN KEY (library_id) REFERENCES field_libraries (id) ON DELETE CASCADE;

CREATE INDEX idx_field_definitions_owner_library ON field_definitions (owner_id, library_id);
