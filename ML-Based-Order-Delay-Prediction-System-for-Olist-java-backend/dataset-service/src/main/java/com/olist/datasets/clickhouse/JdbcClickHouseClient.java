package com.olist.datasets.clickhouse;

import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.exception.StorageException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

final class JdbcClickHouseClient implements ClickHouseClient {

    private static final int BATCH_SIZE = 5000;

    private final Connection connection;
    private final String database;

    JdbcClickHouseClient(ClickHouseProperties properties) {
        this.database = properties.getDatabase();
        try {
            this.connection = DriverManager.getConnection(
                    withDisabledCompression(properties.getUrl()),
                    properties.getUsername(),
                    properties.getPassword());
        } catch (SQLException exception) {
            throw new StorageException("连接 ClickHouse 失败", exception);
        }
    }

    private static String withDisabledCompression(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        StringBuilder adjusted = new StringBuilder(url);
        char separator = url.indexOf('?') >= 0 ? '&' : '?';
        if (!url.contains("compress=")) {
            adjusted.append(separator).append("compress=0");
            separator = '&';
        }
        if (!url.contains("decompress=")) {
            adjusted.append(separator).append("decompress=0");
        }
        return adjusted.toString();
    }

    @Override
    public void execute(String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new StorageException("ClickHouse 执行失败: " + sql, exception);
        }
    }

    @Override
    public Inserter inserter(String table, List<String> columns) {
        String placeholders = columns.stream().map(column -> "?").collect(Collectors.joining(", "));
        String columnList = columns.stream()
                .map(column -> "`" + column + "`")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qualify(table) + " (" + columnList + ") VALUES (" + placeholders + ")";
        try {
            return new JdbcInserter(connection.prepareStatement(sql), columns.size());
        } catch (SQLException exception) {
            throw new StorageException("ClickHouse 插入语句准备失败", exception);
        }
    }

    @Override
    public long countRows(String table) {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count() FROM " + qualify(table))) {
            return result.next() ? result.getLong(1) : 0L;
        } catch (SQLException exception) {
            throw new StorageException("ClickHouse 行数统计失败", exception);
        }
    }

    @Override
    public void dropTable(String table) {
        execute("DROP TABLE IF EXISTS " + qualify(table));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing a failed connection has no recovery path.
        }
    }

    private String qualify(String table) {
        return "`" + database + "`.`" + table + "`";
    }

    private static final class JdbcInserter implements Inserter {

        private final PreparedStatement statement;
        private final int columnCount;
        private int pending;

        private JdbcInserter(PreparedStatement statement, int columnCount) {
            this.statement = statement;
            this.columnCount = columnCount;
        }

        @Override
        public void add(List<String> values) {
            try {
                for (int index = 0; index < columnCount; index++) {
                    String value = index < values.size() ? values.get(index) : null;
                    if (value == null) {
                        statement.setNull(index + 1, Types.VARCHAR);
                    } else {
                        statement.setString(index + 1, value);
                    }
                }
                statement.addBatch();
                pending++;
                if (pending >= BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            } catch (SQLException exception) {
                throw new StorageException("ClickHouse 数据写入失败", exception);
            }
        }

        @Override
        public void close() {
            try {
                if (pending > 0) {
                    statement.executeBatch();
                }
            } catch (SQLException exception) {
                throw new StorageException("ClickHouse 数据提交失败", exception);
            } finally {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                    // Nothing useful can be done when closing the statement fails.
                }
            }
        }
    }
}