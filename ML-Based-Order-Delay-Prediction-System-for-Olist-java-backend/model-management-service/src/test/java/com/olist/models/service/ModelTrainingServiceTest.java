package com.olist.models.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrainingRequest;
import com.olist.models.client.PythonTrainingClient;
import com.olist.models.client.PythonTrainingClient.TrainingResult;
import com.olist.models.client.TrainingDatasetClient;
import com.olist.models.client.TrainingDatasetClient.CatalogColumn;
import com.olist.models.client.TrainingDatasetClient.CatalogTable;
import com.olist.models.client.TrainingDatasetClient.DatasetCatalog;
import com.olist.models.entity.ModelVersion;
import com.olist.models.repository.ModelVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ModelTrainingServiceTest {

    @Mock
    private TrainingDatasetClient datasetClient;

    @Mock
    private PythonTrainingClient pythonClient;

    @Mock
    private ModelVersionRepository repository;

    private ModelTrainingService service;
    private UUID datasetId;
    private UUID versionId;
    private UUID tableId;

    @BeforeEach
    void setUp() {
        service = new ModelTrainingService(datasetClient, pythonClient, repository, new ObjectMapper());
        datasetId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        tableId = UUID.randomUUID();
    }

    @Test
    void trainsOnlyRequestedFieldsAndRegistersOwnerScopedLineage() {
        DatasetCatalog catalog = catalog(datasetId, versionId, "READY", 1_200, tableId);
        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalog);
        when(datasetClient.rows(eq(versionId), eq(tableId), eq(1_200L), any(), eq("caller-token")))
                .thenReturn(List.of(List.of("4.25", "yes"), List.of("8.50", "no")));
        TrainingResult result = new TrainingResult(
                "artifact-1", datasetId, versionId, tableId, "classification", "random_forest", "label",
                List.of("amount"), Map.of("amount", "numeric"), 0.2, 42, Map.of("accuracy", 0.75),
                1_000, 200, 0, Map.of("labels", List.of("no", "yes")), List.of());
        when(pythonClient.train(eq("user-1"), any(), any())).thenReturn(result);
        when(repository.save(any(ModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.train("user-1", "caller-token", request());

        assertEquals(datasetId, response.datasetId());
        assertEquals(versionId, response.datasetVersionId());
        assertEquals("artifact-1", response.artifactId());
        assertEquals(0.75, response.metrics().get("accuracy"));
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(pythonClient).train(eq("user-1"), any(), rows.capture());
        assertEquals(new java.math.BigDecimal("4.25"), rows.getValue().get(0).get("amount"));
        assertEquals("yes", rows.getValue().get(0).get("label"));
        ArgumentCaptor<ModelVersion> stored = ArgumentCaptor.forClass(ModelVersion.class);
        verify(repository).save(stored.capture());
        assertEquals("user-1", stored.getValue().getOwnerId());
        assertEquals(versionId, stored.getValue().getDatasetVersionId());
    }

    @Test
    void rejectsDatasetVersionNotOwnedByRequestedDatasetBeforeReadingRows() {
        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(UUID.randomUUID(), versionId, "READY", 20, tableId));

        assertThrows(ResponseStatusException.class,
                () -> service.train("user-1", "caller-token", request()));

        verify(datasetClient, never()).rows(any(), any(), anyLong(), any(), any());
        verify(pythonClient, never()).train(any(), any(), any());
    }

    @Test
    void rejectsNonReadyVersionsAndTablesWithTooManyRows() {
        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(datasetId, versionId, "UPLOADED", 20, tableId));
        assertThrows(ResponseStatusException.class,
                () -> service.train("user-1", "caller-token", request()));

        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(datasetId, versionId, "READY", 100_001, tableId));
        assertThrows(ResponseStatusException.class,
                () -> service.train("user-1", "caller-token", request()));
        verify(pythonClient, never()).train(any(), any(), any());
    }

    private ModelTrainingRequest request() {
        return new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount"), 0.2, 42);
    }

    private DatasetCatalog catalog(
            UUID expectedDatasetId, UUID expectedVersionId, String status, long rows, UUID expectedTableId) {
        return new DatasetCatalog(expectedDatasetId, expectedVersionId, 3, status, "manifest", "schema",
                List.of(new CatalogTable(expectedTableId, "source::table", "Orders", 0, rows, 2,
                        List.of(new CatalogColumn(UUID.randomUUID(), "amount", 0, "STRING", true),
                                new CatalogColumn(UUID.randomUUID(), "label", 1, "STRING", false)))));
    }
}
