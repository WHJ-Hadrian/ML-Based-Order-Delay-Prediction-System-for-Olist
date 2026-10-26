package com.olist.datasets.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.entity.DatasetColumn;
import com.olist.datasets.entity.DatasetFile;
import com.olist.datasets.entity.DatasetTable;
import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.enums.DatasetStatus;
import com.olist.datasets.exception.StorageException;
import com.olist.datasets.profiler.TablePreview;
import com.olist.datasets.profiler.TablePreviewOptions;
import com.olist.datasets.profiler.TablePreviewReader;
import com.olist.datasets.profiler.TableRowSearch;
import com.olist.datasets.profiler.TableRows;
import com.olist.datasets.storage.DatasetStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ClickHouseDatasetLoaderTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void loadsTableAndVerifiesRowCount() {
        Fixture fixture = new Fixture(true);
        fixture.client.rowCount = 2;

        fixture.loader.load(
                fixture.version,
                List.of(fixture.file),
                List.of(fixture.table),
                List.of(fixture.firstColumn, fixture.secondColumn));

        String expectedTableDdl = "CREATE TABLE `olist_datasets`.`" + fixture.table.getPhysicalTable()
                + "` (`c000` Nullable(String), `c001` Nullable(String))"
                + " ENGINE = MergeTree ORDER BY tuple()";
        assertThat(fixture.client.executed)
                .contains("CREATE DATABASE IF NOT EXISTS `olist_datasets`")
                .contains(expectedTableDdl);
        assertThat(fixture.client.rows).containsExactly(
                List.of("1", "delivered"),
                List.of("2", "shipped"));
        assertThat(fixture.client.dropped).isEmpty();
    }

    @Test
    void dropsCreatedTablesWhenRowCountDoesNotMatch() {
        Fixture fixture = new Fixture(true);
        fixture.client.rowCount = 1;

        assertThatThrownBy(() -> fixture.loader.load(
                        fixture.version,
                        List.of(fixture.file),
                        List.of(fixture.table),
                        List.of(fixture.firstColumn, fixture.secondColumn)))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("导入行数不一致");

        assertThat(fixture.client.dropped).containsExactly(fixture.table.getPhysicalTable());
    }

    @Test
    void dropsTablesWhenRollbackCleanupRuns() {
        Fixture fixture = new Fixture(true);

        fixture.loader.drop(List.of(fixture.table));

        assertThat(fixture.client.dropped).containsExactly(fixture.table.getPhysicalTable());
    }

    @Test
    void skipsEverythingWhenDisabled() {
        Fixture fixture = new Fixture(false);

        fixture.loader.load(
                fixture.version,
                List.of(fixture.file),
                List.of(fixture.table),
                List.of(fixture.firstColumn, fixture.secondColumn));

        assertThat(fixture.client.executed).isEmpty();
        assertThat(fixture.client.rows).isEmpty();
    }

    private static ClickHouseProperties properties(boolean enabled) {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setEnabled(enabled);
        properties.setDatabase("olist_datasets");
        return properties;
    }

    private static final class Fixture {

        private final RecordingClient client = new RecordingClient();
        private final DatasetVersion version;
        private final DatasetFile file;
        private final DatasetTable table;
        private final DatasetColumn firstColumn;
        private final DatasetColumn secondColumn;
        private final ClickHouseDatasetLoader loader;

        private Fixture(boolean enabled) {
            UUID versionId = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            UUID tableId = UUID.randomUUID();
            version = new DatasetVersion(
                    versionId,
                    "orders",
                    null,
                    "orders.csv",
                    "datasets/orders.csv",
                    10L,
                    "sha",
                    "text/csv",
                    DatasetStatus.UPLOADED,
                    1,
                    2L,
                    1,
                    NOW,
                    NOW);
            version.setDatasetId(UUID.randomUUID());
            file = new DatasetFile(
                    fileId, versionId, "orders.csv", "datasets/orders.csv", 10L, "sha", "text/csv", NOW);
            table = new DatasetTable(
                    tableId, versionId, fileId, "orders.csv", "orders", DatasetFormat.CSV, 2L, 2, 0);
            table.setPhysicalTable(ClickHouseNames.table(versionId, tableId));
            table.setTableKey("orders.csv::orders");
            firstColumn = new DatasetColumn(
                    UUID.randomUUID(),
                    versionId,
                    tableId,
                    "order_id",
                    0,
                    "STRING",
                    "STRING",
                    "c000",
                    "Nullable(String)",
                    true,
                    fileId,
                    NOW);
            secondColumn = new DatasetColumn(
                    UUID.randomUUID(),
                    versionId,
                    tableId,
                    "status",
                    1,
                    "STRING",
                    "STRING",
                    "c001",
                    "Nullable(String)",
                    true,
                    fileId,
                    NOW);
            ClickHouseClientFactory factory = new ClickHouseClientFactory() {
                @Override
                public ClickHouseClient create(ClickHouseProperties ignored) {
                    return client;
                }
            };
            loader = new ClickHouseDatasetLoader(
                    properties(enabled), factory, new StubStorage(), new StubReader());
        }
    }

    private static final class RecordingClient implements ClickHouseClient {

        private final List<String> executed = new ArrayList<>();
        private final List<String> dropped = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();
        private long rowCount;

        @Override
        public void execute(String sql) {
            executed.add(sql);
        }

        @Override
        public Inserter inserter(String table, List<String> columns) {
            return new Inserter() {
                @Override
                public void add(List<String> values) {
                    rows.add(new ArrayList<>(values));
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long countRows(String table) {
            return rowCount;
        }

        @Override
        public void dropTable(String table) {
            dropped.add(table);
        }

        @Override
        public void close() {
        }
    }

    private static final class StubStorage implements DatasetStorage {

        @Override
        public void put(String key, InputStream content, long size, String contentType) {
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(String key) {
        }
    }

    private static final class StubReader implements TablePreviewReader {

        @Override
        public TablePreview read(
                String datasetFilename,
                String sourceFile,
                String tableName,
                DatasetFormat tableFormat,
                InputStream input,
                TablePreviewOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TableRows readRows(
                String datasetFilename,
                String sourceFile,
                String tableName,
                DatasetFormat tableFormat,
                InputStream input,
                int offset,
                int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TableRowSearch searchRows(
                String datasetFilename,
                String sourceFile,
                String tableName,
                DatasetFormat tableFormat,
                InputStream input,
                String keyword,
                int columnIndex,
                long offset,
                int limit,
                long maxScannedRows) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachRow(
                String datasetFilename,
                String sourceFile,
                String tableName,
                DatasetFormat tableFormat,
                InputStream input,
                Consumer<List<String>> consumer) {
            consumer.accept(List.of("1", "delivered"));
            consumer.accept(List.of("2", "shipped"));
        }
    }
}