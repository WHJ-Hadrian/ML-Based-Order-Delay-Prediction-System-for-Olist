package com.olist.datasets;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatasetV10MigrationTest {

    private static final String VERSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String VERSION_2_ID = "22222222-2222-2222-2222-222222222222";
    private static final String FILE_ID = "33333333-3333-3333-3333-333333333333";
    private static final String TABLE_ID = "44444444-4444-4444-4444-444444444444";
    private static final String TABLE_2_ID = "55555555-5555-5555-5555-555555555555";
    private static final String COLUMN_ID = "66666666-6666-6666-6666-666666666666";
    private static final String COLUMN_2_ID = "77777777-7777-7777-7777-777777777777";
    private static final String FIELD_ID = "88888888-8888-8888-8888-888888888888";
    private static final String BINDING_ID = "99999999-9999-9999-9999-999999999999";
    private static final String BINDING_2_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void migratesFromV9AndAllowsFieldReuseAcrossVersions() {
        JdbcDataSource dataSource = newDataSource();
        migrate(dataSource, "9");
        seedV9(dataSource);
        migrate(dataSource, "10");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(count(jdbc, "SELECT COUNT(*) FROM datasets")).isEqualTo(1);
        assertThat(text(jdbc, "SELECT current_version_id FROM datasets WHERE id = ?", VERSION_ID))
                .isEqualTo(VERSION_ID);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM dataset_versions WHERE id = ? AND dataset_id = ? AND version_no = 1 AND source_type = ?",
                VERSION_ID, VERSION_ID, "UPLOAD")).isEqualTo(1);
        assertThat(text(jdbc, "SELECT table_key FROM dataset_tables WHERE id = ?", TABLE_ID)).isEqualTo(TABLE_ID);
        assertThat(text(jdbc, "SELECT physical_table FROM dataset_tables WHERE id = ?", TABLE_ID)).isNull();
        assertThat(text(jdbc, "SELECT dataset_version_id FROM dataset_columns WHERE id = ?", COLUMN_ID)).isEqualTo(VERSION_ID);
        assertThat(text(jdbc, "SELECT logical_type FROM dataset_columns WHERE id = ?", COLUMN_ID)).isEqualTo("STRING");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM dataset_columns WHERE id = ? AND physical_name IS NULL AND clickhouse_type IS NULL AND nullable = TRUE",
                COLUMN_ID)).isEqualTo(1);
        assertThat(text(jdbc, "SELECT dataset_version_id FROM dataset_field_bindings WHERE id = ?", BINDING_ID)).isEqualTo(VERSION_ID);
        assertThat(text(jdbc, "SELECT binding_status FROM dataset_field_bindings WHERE id = ?", BINDING_ID)).isEqualTo("BOUND");
        assertThat(text(jdbc, "SELECT blob_sha256 FROM dataset_files WHERE id = ?", FILE_ID)).isEqualTo(SHA256);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM storage_blobs WHERE sha256 = ?", SHA256)).isEqualTo(1);
        assertThat(text(jdbc, "SELECT storage_key FROM storage_blobs WHERE sha256 = ?", SHA256)).isEqualTo("datasets/orders.csv");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = ?",
                "UK_DATASET_FIELD_BINDINGS_FIELD")).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) = ?",
                "UK_DATASET_FIELD_BINDINGS_FIELD_VERSION")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                "DATASET_COLUMNS", "DATASET_ID")).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                "DATASET_FIELD_BINDINGS", "DATASET_ID")).isZero();
        assertThat(text(jdbc, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                "DATASET_FIELD_BINDINGS", "TABLE_ID")).isEqualTo("YES");
        assertThat(text(jdbc, "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?",
                "DATASET_FIELD_BINDINGS", "COLUMN_ID")).isEqualTo("YES");

        insertSecondVersion(jdbc);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM dataset_field_bindings WHERE field_id = ?", FIELD_ID)).isEqualTo(2);
    }

    private void seedV9(JdbcDataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Timestamp now = Timestamp.valueOf("2026-01-01 00:00:00");
        jdbc.update("INSERT INTO dataset_versions (id, name, original_filename, storage_key, size_bytes, sha256, content_type, status, created_at, updated_at, description, table_count, row_count, file_count, owner_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                VERSION_ID, "orders", "orders.csv", "datasets/orders.csv", 10L, SHA256, "text/csv", "READY", now, now, "orders dataset", 1, 2L, 1, "owner-1");
        jdbc.update("INSERT INTO dataset_files (id, dataset_id, original_filename, storage_key, size_bytes, sha256, content_type, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                FILE_ID, VERSION_ID, "orders.csv", "datasets/orders.csv", 10L, SHA256, "text/csv", now);
        jdbc.update("INSERT INTO dataset_tables (id, dataset_id, table_name, table_format, row_count, column_count, ordinal, file_id, file_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TABLE_ID, VERSION_ID, "orders", "CSV", 2L, 2, 0, FILE_ID, "orders.csv");
        jdbc.update("INSERT INTO field_definitions (id, owner_id, name, display_name, data_type, semantic_role, source_table, description, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                FIELD_ID, "owner-1", "order_id", "Order ID", "STRING", "IDENTIFIER", "orders", null, "ACTIVE", now, now);
        jdbc.update("INSERT INTO dataset_columns (id, dataset_id, table_id, column_name, ordinal, data_type, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                COLUMN_ID, VERSION_ID, TABLE_ID, "order_id", 0, "STRING", now);
        jdbc.update("INSERT INTO dataset_field_bindings (id, owner_id, field_id, dataset_id, table_id, column_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                BINDING_ID, "owner-1", FIELD_ID, VERSION_ID, TABLE_ID, COLUMN_ID, now);
    }

    private void insertSecondVersion(JdbcTemplate jdbc) {
        Timestamp now = Timestamp.valueOf("2026-01-02 00:00:00");
        jdbc.update("INSERT INTO dataset_versions (id, name, original_filename, storage_key, size_bytes, sha256, content_type, status, created_at, updated_at, description, table_count, row_count, file_count, owner_id, dataset_id, version_no, source_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                VERSION_2_ID, "orders", "orders-v2.csv", "datasets/orders-v2.csv", 12L, SHA256, "text/csv", "READY", now, now, null, 1, 2L, 1, "owner-1", VERSION_ID, 2, "UPLOAD");
        jdbc.update("INSERT INTO dataset_tables (id, dataset_id, table_name, table_format, row_count, column_count, ordinal, file_id, file_name, table_key, physical_table) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TABLE_2_ID, VERSION_2_ID, "orders", "CSV", 2L, 1, 0, null, "orders-v2.csv", "orders-v2.csv::orders", "ds_table_2");
        jdbc.update("INSERT INTO dataset_columns (id, dataset_version_id, table_id, column_name, ordinal, data_type, created_at, logical_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                COLUMN_2_ID, VERSION_2_ID, TABLE_2_ID, "order_id", 0, "STRING", now, "STRING");
        jdbc.update("INSERT INTO dataset_field_bindings (id, owner_id, field_id, dataset_version_id, table_id, column_id, created_at, binding_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                BINDING_2_ID, "owner-1", FIELD_ID, VERSION_2_ID, TABLE_2_ID, COLUMN_2_ID, now, "BOUND");
    }

    private JdbcDataSource newDataSource() {
        String databaseName = "v10_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void migrate(JdbcDataSource dataSource, String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private String text(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
