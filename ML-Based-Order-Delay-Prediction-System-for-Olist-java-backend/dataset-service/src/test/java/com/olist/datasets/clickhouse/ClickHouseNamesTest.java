package com.olist.datasets.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClickHouseNamesTest {

    @Test
    void buildsVersionScopedTableName() {
        UUID versionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID tableId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(ClickHouseNames.table(versionId, tableId))
                .isEqualTo("ds_11111111111111111111111111111111_22222222222222222222222222222222");
    }

    @Test
    void buildsPaddedColumnName() {
        assertThat(ClickHouseNames.column(0)).isEqualTo("c000");
        assertThat(ClickHouseNames.column(12)).isEqualTo("c012");
        assertThat(ClickHouseNames.column(1234)).isEqualTo("c1234");
    }

    @Test
    void mapsLogicalTypes() {
        assertThat(ClickHouseNames.type("STRING", true)).isEqualTo("Nullable(String)");
        assertThat(ClickHouseNames.type("INTEGER", true)).isEqualTo("Nullable(Int64)");
        assertThat(ClickHouseNames.type("DOUBLE", false)).isEqualTo("Float64");
        assertThat(ClickHouseNames.type(null, true)).isEqualTo("Nullable(String)");
    }
}