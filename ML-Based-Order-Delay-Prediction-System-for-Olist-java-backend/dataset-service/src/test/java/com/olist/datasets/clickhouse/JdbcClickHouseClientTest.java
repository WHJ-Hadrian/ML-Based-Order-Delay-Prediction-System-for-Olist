package com.olist.datasets.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdbcClickHouseClientTest {

    @Test
    void disablesCompressionAndDecompressionWhenUrlHasNoQuery() {
        assertThat(JdbcClickHouseClient.withDisabledCompression("jdbc:clickhouse://localhost:8123"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?compress=0&decompress=0");
    }

    @Test
    void appendsCompressionFlagToExistingQuery() {
        assertThat(JdbcClickHouseClient.withDisabledCompression("jdbc:clickhouse://localhost:8123?database=olist"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?database=olist&compress=0&decompress=0");
    }

    @Test
    void preservesExplicitCompressionSetting() {
        assertThat(JdbcClickHouseClient.withDisabledCompression("jdbc:clickhouse://localhost:8123?compress=1"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?compress=1&decompress=0");
        assertThat(JdbcClickHouseClient.withDisabledCompression("jdbc:clickhouse://localhost:8123?COMPRESSION=lz4"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?COMPRESSION=lz4&compress=0&decompress=0");
    }

    @Test
    void keepsBlankUrlUnchanged() {
        assertThat(JdbcClickHouseClient.withDisabledCompression(null)).isNull();
        assertThat(JdbcClickHouseClient.withDisabledCompression("  ")).isEqualTo("  ");
    }
}
