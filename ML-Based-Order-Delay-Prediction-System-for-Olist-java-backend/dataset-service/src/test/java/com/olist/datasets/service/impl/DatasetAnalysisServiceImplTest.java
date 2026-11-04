package com.olist.datasets.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.olist.datasets.clickhouse.ClickHouseClient;
import com.olist.datasets.clickhouse.ClickHouseClientFactory;
import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.enums.DatasetStatus;
import com.olist.datasets.exception.DatasetNotReadyException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.DatasetColumnAnalysisVO;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DatasetAnalysisServiceImplTest {

    private final UUID datasetId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID tableId = UUID.randomUUID();
    private final UUID columnId = UUID.randomUUID();
    private final DatasetService datasetService = mock(DatasetService.class);
    private final ClickHouseProperties properties = new ClickHouseProperties();
    private final ClickHouseClientFactory factory = mock(ClickHouseClientFactory.class);
    private final ClickHouseClient client = mock(ClickHouseClient.class);
    private DatasetAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        properties.setDatabase("olist_datasets");
        properties.setEnabled(true);
        when(factory.create(properties)).thenReturn(client);
        service = new DatasetAnalysisServiceImpl(datasetService, properties, factory);
    }

    @Test
    void categoricalAnalysisUsesBoundedFrequencyQuery() {
        stubMetadata("Nullable(String)", "STRING");
        when(client.query(anyString()))
                .thenReturn(List.of(List.of(2L, 3L)))
                .thenReturn(List.of(List.of("paid", 8L), List.of("pending", 2L)));

        DatasetColumnAnalysisVO result = service.analyzeColumn(datasetId, tableId, columnId);

        assertEquals("categories", result.chartType());
        assertEquals(2, result.missingCount());
        assertEquals(3, result.uniqueCountEstimate());
        assertEquals("paid", result.points().get(0).label());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(2)).query(sql.capture());
        assertTrue(sql.getAllValues().get(1).contains("LIMIT 12"));
        assertTrue(sql.getAllValues().get(1).contains("GROUP BY `c000`"));
        assertTrue(sql.getAllValues().get(1).contains("max_execution_time = 30"));
    }

    @Test
    void numericAnalysisReturnsStableHistogramBuckets() {
        stubMetadata("Nullable(Float64)", "DOUBLE");
        when(client.query(anyString()))
                .thenReturn(List.of(List.of(1L, 10L, 0.0, 11.0)))
                .thenReturn(List.of(List.of(0, 3L), List.of(11, 1L)));

        DatasetColumnAnalysisVO result = service.analyzeColumn(datasetId, tableId, columnId);

        assertEquals("histogram", result.chartType());
        assertEquals(12, result.points().size());
        assertEquals(3, result.points().get(0).value());
        assertEquals(1, result.points().get(11).value());
        assertEquals(0.0, result.minimum());
        assertEquals(11.0, result.maximum());
    }

    @Test
    void categoryAndNumericRelationReturnsBoundedGroupAverages() {
        UUID categoryId = UUID.randomUUID();
        stubRelationMetadata(categoryId, "STRING", "Nullable(String)");
        when(client.query(anyString())).thenReturn(List.of(List.of("active", 12.5, 8L)));

        var result = service.analyzeRelation(datasetId, tableId, columnId, categoryId);

        assertEquals("category-average", result.chartType());
        assertEquals("customer status", result.xColumnName());
        assertEquals("group", result.yColumnName());
        assertEquals("active", result.points().get(0).label());
        assertEquals(12.5, result.points().get(0).y());
        assertEquals(8, result.points().get(0).rowCount());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(client).query(sql.capture());
        assertTrue(sql.getValue().contains("avg(toFloat64OrNull(`c000`))"));
        assertTrue(sql.getValue().contains("GROUP BY `c001`"));
        assertTrue(sql.getValue().contains("LIMIT 12"));
    }

    @Test
    void numericRelationReturnsAtMost250DeterministicallyOrderedPoints() {
        UUID secondNumericId = UUID.randomUUID();
        stubRelationMetadata(secondNumericId, "DECIMAL", "Nullable(String)");
        when(client.query(anyString())).thenReturn(List.of(List.of(1.25, 8.5), List.of(2.0, 9.5)));

        var result = service.analyzeRelation(datasetId, tableId, columnId, secondNumericId);

        assertEquals("scatter", result.chartType());
        assertEquals(2, result.points().size());
        assertEquals(1.25, result.points().get(0).x());
        assertEquals(9.5, result.points().get(1).y());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(client).query(sql.capture());
        assertTrue(sql.getValue().contains("ORDER BY cityHash64"));
        assertTrue(sql.getValue().contains("LIMIT 250"));
        assertTrue(sql.getValue().contains("max_result_rows = 250"));
        assertTrue(sql.getValue().contains("max_rows_to_read = 5000000"));
        assertTrue(sql.getValue().contains("max_bytes_to_read = 1000000000"));
    }

    @Test
    void rejectsCategoricalPairWithoutRunningQuery() {
        UUID secondColumnId = UUID.randomUUID();
        stubRelationMetadata(secondColumnId, "STRING", "STRING", "Nullable(String)");

        assertThrows(InvalidDatasetException.class,
                () -> service.analyzeRelation(datasetId, tableId, columnId, secondColumnId));

        verify(factory, never()).create(properties);
    }

    @Test
    void rejectsColumnThatDoesNotBelongToSelectedTable() {
        stubMetadata("Nullable(String)", "STRING");
        DatasetCatalogVO.CatalogTableVO table = new DatasetCatalogVO.CatalogTableVO(
                tableId,
                "table_1",
                "customers",
                0,
                10,
                1,
                List.of());
        when(datasetService.catalog(datasetId)).thenReturn(new DatasetCatalogVO(
                datasetId,
                versionId,
                1,
                null,
                DatasetStatus.READY,
                "manifest",
                "schema",
                Instant.now(),
                List.of(table)));

        assertThrows(
                InvalidDatasetException.class,
                () -> service.analyzeColumn(datasetId, tableId, columnId));
        verify(datasetService, never()).executionSnapshot(datasetId);
        verify(factory, never()).create(properties);
    }

    @Test
    void explainsWhenClickHouseAnalysisIsDisabled() {
        properties.setEnabled(false);
        stubMetadata("Nullable(String)", "STRING");

        assertThrows(
                DatasetNotReadyException.class,
                () -> service.analyzeColumn(datasetId, tableId, columnId));
        verify(factory, never()).create(properties);
    }

    private void stubMetadata(String clickhouseType, String logicalType) {
        DatasetCatalogVO.CatalogColumnVO catalogColumn = new DatasetCatalogVO.CatalogColumnVO(
                columnId,
                "customer status",
                0,
                logicalType,
                true);
        DatasetCatalogVO.CatalogTableVO catalogTable = new DatasetCatalogVO.CatalogTableVO(
                tableId,
                "table_1",
                "customers",
                0,
                10,
                1,
                List.of(catalogColumn));
        when(datasetService.catalog(datasetId)).thenReturn(new DatasetCatalogVO(
                datasetId,
                versionId,
                1,
                null,
                DatasetStatus.READY,
                "manifest",
                "schema",
                Instant.now(),
                List.of(catalogTable)));

        ExecutionSnapshotVO.SnapshotColumnVO snapshotColumn = new ExecutionSnapshotVO.SnapshotColumnVO(
                columnId,
                null,
                "customer status",
                "c000",
                logicalType,
                clickhouseType,
                true);
        ExecutionSnapshotVO.SnapshotTableVO snapshotTable = new ExecutionSnapshotVO.SnapshotTableVO(
                tableId,
                "table_1",
                "customers",
                "ds_0123456789abcdef0123456789abcdef_abcdef0123456789abcdef0123456789",
                10,
                List.of(snapshotColumn));
        when(datasetService.executionSnapshot(datasetId)).thenReturn(new ExecutionSnapshotVO(
                versionId,
                "READY",
                "schema",
                "manifest",
                "olist_datasets",
                10,
                List.of(snapshotTable)));
    }

    private void stubRelationMetadata(UUID secondColumnId, String secondLogicalType, String secondClickhouseType) {
        stubRelationMetadata(secondColumnId, "DOUBLE", secondLogicalType, secondClickhouseType);
    }

    private void stubRelationMetadata(
            UUID secondColumnId, String firstLogicalType, String secondLogicalType, String secondClickhouseType) {
        List<DatasetCatalogVO.CatalogColumnVO> catalogColumns = List.of(
                new DatasetCatalogVO.CatalogColumnVO(columnId, "customer status", 0, firstLogicalType, true),
                new DatasetCatalogVO.CatalogColumnVO(secondColumnId, "group", 1, secondLogicalType, true));
        DatasetCatalogVO.CatalogTableVO catalogTable = new DatasetCatalogVO.CatalogTableVO(
                tableId, "table_1", "customers", 0, 10, 2, catalogColumns);
        when(datasetService.catalog(datasetId)).thenReturn(new DatasetCatalogVO(
                datasetId, versionId, 1, null, DatasetStatus.READY, "manifest", "schema",
                Instant.now(), List.of(catalogTable)));

        List<ExecutionSnapshotVO.SnapshotColumnVO> snapshotColumns = List.of(
                new ExecutionSnapshotVO.SnapshotColumnVO(
                        columnId, null, "customer status", "c000", firstLogicalType, "Nullable(String)", true),
                new ExecutionSnapshotVO.SnapshotColumnVO(
                        secondColumnId, null, "group", "c001", secondLogicalType, secondClickhouseType, true));
        ExecutionSnapshotVO.SnapshotTableVO snapshotTable = new ExecutionSnapshotVO.SnapshotTableVO(
                tableId, "table_1", "customers",
                "ds_0123456789abcdef0123456789abcdef_abcdef0123456789abcdef0123456789", 10, snapshotColumns);
        when(datasetService.executionSnapshot(datasetId)).thenReturn(new ExecutionSnapshotVO(
                versionId, "READY", "schema", "manifest", "olist_datasets", 10, List.of(snapshotTable)));
    }
}
