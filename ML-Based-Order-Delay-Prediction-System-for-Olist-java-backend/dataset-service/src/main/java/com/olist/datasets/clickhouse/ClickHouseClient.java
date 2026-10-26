package com.olist.datasets.clickhouse;

import java.util.List;

public interface ClickHouseClient extends AutoCloseable {

    void execute(String sql);

    Inserter inserter(String table, List<String> columns);

    long countRows(String table);

    void dropTable(String table);

    @Override
    void close();

    interface Inserter extends AutoCloseable {

        void add(List<String> values);

        @Override
        void close();
    }
}