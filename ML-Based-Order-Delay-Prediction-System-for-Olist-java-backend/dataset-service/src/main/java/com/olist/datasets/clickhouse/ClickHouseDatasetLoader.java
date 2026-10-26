package com.olist.datasets.clickhouse;

import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.entity.DatasetColumn;
import com.olist.datasets.entity.DatasetFile;
import com.olist.datasets.entity.DatasetTable;
import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.exception.StorageException;
import com.olist.datasets.profiler.TablePreviewReader;
import com.olist.datasets.storage.DatasetStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ClickHouseDatasetLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseDatasetLoader.class);

    private final ClickHouseProperties properties;
    private final ClickHouseClientFactory clientFactory;
    private final DatasetStorage datasetStorage;
    private final TablePreviewReader tablePreviewReader;

    public ClickHouseDatasetLoader(
            ClickHouseProperties properties,
            ClickHouseClientFactory clientFactory,
            DatasetStorage datasetStorage,
            TablePreviewReader tablePreviewReader) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.datasetStorage = datasetStorage;
        this.tablePreviewReader = tablePreviewReader;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void load(
            DatasetVersion version,
            List<DatasetFile> files,
            List<DatasetTable> tables,
            List<DatasetColumn> columns) {
        if (!isEnabled()) {
            return;
        }
        Map<UUID, DatasetFile> filesById = new HashMap<>();
        for (DatasetFile file : files) {
            filesById.put(file.getId(), file);
        }
        Map<UUID, List<DatasetColumn>> columnsByTable = new HashMap<>();
        for (DatasetColumn column : columns) {
            columnsByTable
                    .computeIfAbsent(column.getTableId(), ignored -> new ArrayList<>())
                    .add(column);
        }

        List<String> createdTables = new ArrayList<>();
        try (ClickHouseClient client = clientFactory.create(properties)) {
            client.execute("CREATE DATABASE IF NOT EXISTS `" + properties.getDatabase() + "`");
            try {
                for (DatasetTable table : tables) {
                    List<DatasetColumn> tableColumns = columnsByTable
                            .getOrDefault(table.getId(), List.of())
                            .stream()
                            .sorted(Comparator.comparingInt(DatasetColumn::getOrdinal))
                            .toList();
                    if (tableColumns.isEmpty()) {
                        throw new StorageException("数据表 " + table.getTableName() + " 缺少列元数据");
                    }
                    if (table.getPhysicalTable() == null || table.getPhysicalTable().isBlank()) {
                        throw new StorageException("数据表 " + table.getTableName() + " 缺少 ClickHouse 物理表名");
                    }
                    client.execute(createTableSql(table, tableColumns));
                    createdTables.add(table.getPhysicalTable());
                    insertRows(client, table, tableColumns, filesById);
                    long actualRows = client.countRows(table.getPhysicalTable());
                    if (actualRows != table.getRowCount()) {
                        throw new StorageException("数据表 " + table.getTableName()
                                + " 导入行数不一致，期望 " + table.getRowCount() + "，实际 " + actualRows);
                    }
                }
            } catch (RuntimeException exception) {
                dropQuietly(client, createdTables);
                throw exception;
            }
        }
        LOGGER.info(
                "Loaded dataset version {} into ClickHouse database {}",
                version.getId(),
                properties.getDatabase());
    }

    public void drop(List<DatasetTable> tables) {
        if (!isEnabled()) {
            return;
        }
        try (ClickHouseClient client = clientFactory.create(properties)) {
            dropQuietly(client, tables.stream()
                    .map(DatasetTable::getPhysicalTable)
                    .filter(table -> table != null && !table.isBlank())
                    .toList());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to clean up ClickHouse tables after rollback", exception);
        }
    }
    private void insertRows(
            ClickHouseClient client,
            DatasetTable table,
            List<DatasetColumn> tableColumns,
            Map<UUID, DatasetFile> filesById) {
        DatasetFile file = filesById.get(table.getFileId());
        if (file == null) {
            throw new StorageException("数据表 " + table.getTableName() + " 找不到来源文件");
        }
        List<String> physicalColumns = tableColumns.stream()
                .map(DatasetColumn::getPhysicalName)
                .toList();
        try (InputStream input = datasetStorage.open(file.getStorageKey());
                ClickHouseClient.Inserter inserter =
                        client.inserter(table.getPhysicalTable(), physicalColumns)) {
            tablePreviewReader.forEachRow(
                    file.getOriginalFilename(),
                    table.getFileName(),
                    table.getTableName(),
                    table.getTableFormat(),
                    input,
                    row -> inserter.add(padRow(row, tableColumns.size())));
        } catch (IOException exception) {
            throw new StorageException("读取数据集文件失败", exception);
        }
    }

    private String createTableSql(DatasetTable table, List<DatasetColumn> columns) {
        String columnDefinitions = columns.stream()
                .map(column -> "`" + column.getPhysicalName() + "` " + column.getClickhouseType())
                .collect(Collectors.joining(", "));
        return "CREATE TABLE " + qualify(table.getPhysicalTable()) + " (" + columnDefinitions
                + ") ENGINE = MergeTree ORDER BY tuple()";
    }

    private String qualify(String table) {
        return "`" + properties.getDatabase() + "`.`" + table + "`";
    }

    private List<String> padRow(List<String> row, int columnCount) {
        List<String> values = new ArrayList<>(columnCount);
        for (int index = 0; index < columnCount; index++) {
            values.add(index < row.size() ? row.get(index) : null);
        }
        return values;
    }

    private void dropQuietly(ClickHouseClient client, List<String> tables) {
        for (String table : tables) {
            try {
                client.dropTable(table);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to drop ClickHouse table {} after import failure", table, exception);
            }
        }
    }
}