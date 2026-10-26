CREATE TABLE dataset_columns (
    id CHAR(36) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    table_id CHAR(36) NOT NULL,
    column_name VARCHAR(512) NOT NULL,
    ordinal INT NOT NULL,
    data_type VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_dataset_columns PRIMARY KEY (id),
    CONSTRAINT fk_dataset_columns_dataset FOREIGN KEY (dataset_id)
        REFERENCES dataset_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_dataset_columns_table FOREIGN KEY (table_id)
        REFERENCES dataset_tables (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_dataset_columns_table_ordinal
    ON dataset_columns (table_id, ordinal);
CREATE UNIQUE INDEX uk_dataset_columns_table_name
    ON dataset_columns (table_id, column_name);
CREATE INDEX idx_dataset_columns_dataset_id ON dataset_columns (dataset_id);

CREATE TABLE dataset_field_bindings (
    id CHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    field_id CHAR(36) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    table_id CHAR(36) NOT NULL,
    column_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_dataset_field_bindings PRIMARY KEY (id),
    CONSTRAINT fk_dataset_field_bindings_field FOREIGN KEY (field_id)
        REFERENCES field_definitions (id) ON DELETE CASCADE,
    CONSTRAINT fk_dataset_field_bindings_dataset FOREIGN KEY (dataset_id)
        REFERENCES dataset_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_dataset_field_bindings_table FOREIGN KEY (table_id)
        REFERENCES dataset_tables (id) ON DELETE CASCADE,
    CONSTRAINT fk_dataset_field_bindings_column FOREIGN KEY (column_id)
        REFERENCES dataset_columns (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_dataset_field_bindings_column ON dataset_field_bindings (column_id);
CREATE UNIQUE INDEX uk_dataset_field_bindings_field ON dataset_field_bindings (field_id);
CREATE INDEX idx_dataset_field_bindings_owner_dataset
    ON dataset_field_bindings (owner_id, dataset_id);
