ALTER TABLE dataset_versions ADD COLUMN description VARCHAR(1000);
ALTER TABLE dataset_versions ADD COLUMN table_count INT NOT NULL DEFAULT 0;
ALTER TABLE dataset_versions ADD COLUMN row_count BIGINT NOT NULL DEFAULT 0;

CREATE TABLE dataset_tables (
    id CHAR(36) NOT NULL,
    dataset_id CHAR(36) NOT NULL,
    table_name VARCHAR(512) NOT NULL,
    table_format VARCHAR(16) NOT NULL,
    row_count BIGINT NOT NULL,
    column_count INT NOT NULL,
    ordinal INT NOT NULL,
    CONSTRAINT pk_dataset_tables PRIMARY KEY (id),
    CONSTRAINT fk_dataset_tables_dataset FOREIGN KEY (dataset_id)
        REFERENCES dataset_versions (id) ON DELETE CASCADE
);

CREATE INDEX idx_dataset_tables_dataset_id ON dataset_tables (dataset_id);