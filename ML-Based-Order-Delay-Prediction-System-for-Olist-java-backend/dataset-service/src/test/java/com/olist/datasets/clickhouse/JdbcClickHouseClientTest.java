package com.olist.datasets.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JdbcClickHouseClientTest {

    @Test
    void disablesCompressionWhenUrlHasNoQuery() {
        assertThat(JdbcClickHouseClient.normalizeUrl("jdbc:clickhouse://localhost:8123"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?compress=0");
    }

    @Test
    void appendsCompressionFlagToExistingQuery() {
        assertThat(JdbcClickHouseClient.normalizeUrl("jdbc:clickhouse://localhost:8123?database=olist"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?database=olist&compress=0");
    }

    @Test
    void preservesExplicitCompressionSetting() {
        assertThat(JdbcClickHouseClient.normalizeUrl("jdbc:clickhouse://localhost:8123?compress=1"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?compress=1");
        assertThat(JdbcClickHouseClient.normalizeUrl("jdbc:clickhouse://localhost:8123?COMPRESSION=lz4"))
                .isEqualTo("jdbc:clickhouse://localhost:8123?COMPRESSION=lz4");
    }

    @Test
    void keepsBlankUrlUnchanged() {
        assertThat(JdbcClickHouseClient.normalizeUrl(null)).isNull();
        assertThat(JdbcClickHouseClient.normalizeUrl("  ")).isEqualTo("  ");
    }
}
