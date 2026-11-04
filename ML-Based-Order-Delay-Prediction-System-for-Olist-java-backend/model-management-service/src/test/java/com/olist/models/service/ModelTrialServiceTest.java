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
import com.olist.models.api.ModelTrialRequest;
import com.olist.models.api.ModelTrialCompareRequest;
import com.olist.models.api.ModelTrialTestSource;
import com.olist.models.client.PythonTrainingClient;
import com.olist.models.client.PythonTrainingClient.TrialResult;
import com.olist.models.client.TrainingDatasetClient;
import com.olist.models.client.TrainingDatasetClient.CatalogColumn;
import com.olist.models.client.TrainingDatasetClient.CatalogTable;
import com.olist.models.client.TrainingDatasetClient.DatasetCatalog;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.ModelTrial;
import com.olist.models.repository.ModelTrialRepository;
import com.olist.models.repository.ModelVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ModelTrialServiceTest {

    @Mock private TrainingDatasetClient datasetClient;
    @Mock private PythonTrainingClient pythonClient;
    @Mock private ModelVersionRepository modelRepository;
    @Mock private ModelTrialRepository trialRepository;

    private ModelTrialService service;
    private UUID modelId;
    private UUID datasetId;
    private UUID versionId;
    private UUID tableId;

    @BeforeEach
    void setUp() {
        service = new ModelTrialService(
                datasetClient, pythonClient, modelRepository, trialRepository, new ObjectMapper());
        modelId = UUID.randomUUID();
        datasetId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        tableId = UUID.randomUUID();
    }

    @Test
    void predictsWithRegisteredArtifactAndPersistsIndependentDatasetLineage() {
        ModelVersion model = model();
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-a")).thenReturn(Optional.of(model));
        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalog("READY", 40));
        when(datasetClient.rows(eq(versionId), eq(tableId), eq(40L), any(), eq("caller-token")))
                .thenReturn(List.of(List.of("12.50", "yes")));
        when(pythonClient.trial(eq("owner-a"), eq("artifact-1"), any(), eq("label")))
                .thenReturn(new TrialResult("classification", "label", 40, 40, 0,
                        Map.of("accuracy", 0.9), Map.of("labels", List.of("yes")), List.of(Map.of("row", 1))));
        when(trialRepository.save(any(ModelTrial.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.trial("owner-a", "caller-token", modelId,
                new ModelTrialRequest(datasetId, versionId, tableId, "label"));

        assertEquals(modelId, response.modelId());
        assertEquals(versionId, response.datasetVersionId());
        assertEquals("label", response.targetColumn());
        assertEquals(40, response.evaluatedRows());
        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(pythonClient).trial(eq("owner-a"), eq("artifact-1"), rows.capture(), eq("label"));
        assertEquals(new java.math.BigDecimal("12.50"), rows.getValue().get(0).get("amount"));
        assertEquals("yes", rows.getValue().get(0).get("label"));
        ArgumentCaptor<ModelTrial> saved = ArgumentCaptor.forClass(ModelTrial.class);
        verify(trialRepository).save(saved.capture());
        assertEquals("owner-a", saved.getValue().getOwnerId());
        assertEquals(datasetId, saved.getValue().getDatasetId());
    }

    @Test
    void rejectsModelsOwnedByAnotherUserBeforeReadingDataset() {
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-b")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.trial("owner-b", "caller-token", modelId,
                new ModelTrialRequest(datasetId, versionId, tableId, "label")));

        verify(datasetClient, never()).catalog(any(), any());
        verify(pythonClient, never()).trial(any(), any(), any(), any());
    }

    @Test
    void rejectsMissingFeatureColumnsAndNonReadyVersionsBeforeLoadingRows() {
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-a")).thenReturn(Optional.of(model()));
        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalog("UPLOADED", 40));
        assertThrows(ResponseStatusException.class, () -> service.trial("owner-a", "caller-token", modelId,
                new ModelTrialRequest(datasetId, versionId, tableId, "label")));

        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalog("READY", 40, "label"));
        assertThrows(ResponseStatusException.class, () -> service.trial("owner-a", "caller-token", modelId,
                new ModelTrialRequest(datasetId, versionId, tableId, "label")));

        verify(datasetClient, never()).rows(any(), any(), anyLong(), any(), any());
        verify(pythonClient, never()).trial(any(), any(), any(), any());
    }

    @Test
    void rejectsTrainingDatasetAsTrialDataBeforeReadingRows() {
        ModelVersion trainedOnSelectedDataset = new ModelVersion(
                modelId, "owner-a", "Revenue model", "classification", "random_forest",
                datasetId, UUID.randomUUID(), 1, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 42, "{}", 32, 8, 0,
                "artifact-1", null, "[]", Instant.now());
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-a"))
                .thenReturn(Optional.of(trainedOnSelectedDataset));
        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalog("READY", 40));

        assertThrows(ResponseStatusException.class, () -> service.trial("owner-a", "caller-token", modelId,
                new ModelTrialRequest(datasetId, versionId, tableId, "label")));

        verify(datasetClient, never()).rows(any(), any(), anyLong(), any(), any());
        verify(pythonClient, never()).trial(any(), any(), any(), any());
        verify(trialRepository, never()).save(any());
    }

    @Test
    void rejectsComparisonDatasetUsedToTrainAnySelectedModel() {
        UUID secondId = UUID.randomUUID();
        ModelVersion first = model();
        ModelVersion trainedOnSelectedDataset = new ModelVersion(
                secondId, "owner-a", "Revenue model 2", "classification", "logistic_regression",
                datasetId, UUID.randomUUID(), 1, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 84, "{}", 32, 8, 0,
                "artifact-2", null, "[]", Instant.now());
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-a")).thenReturn(Optional.of(first));
        when(modelRepository.findByIdAndOwnerId(secondId, "owner-a"))
                .thenReturn(Optional.of(trainedOnSelectedDataset));
        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalog("READY", 40));

        assertThrows(ResponseStatusException.class, () -> service.compare(
                "owner-a", "caller-token", new ModelTrialCompareRequest(
                        List.of(new ModelTrialTestSource(datasetId, versionId, tableId)),
                        List.of(modelId, secondId), "label")));

        verify(datasetClient).catalog(versionId, "caller-token");
        verify(datasetClient, never()).rows(any(), any(), anyLong(), any(), any());
        verify(pythonClient, never()).trial(any(), any(), any(), any());
    }

    @Test
    void comparesModelsAcrossTestSourcesReadingEachSourceOnceAndPersistingSharedLineage() {
        ModelVersion first = model();
        UUID secondId = UUID.randomUUID();
        ModelVersion second = new ModelVersion(secondId, "owner-a", "Revenue model 2", "classification",
                "logistic_regression", UUID.randomUUID(), UUID.randomUUID(), 1, tableId, "label", "[\"category\",\"amount\"]",
                "{\"category\":\"categorical\",\"amount\":\"numeric\"}", 0.2, 84, "{}", 32, 8, 0,
                "artifact-2", null, "[]", Instant.now());
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-a")).thenReturn(Optional.of(first));
        when(modelRepository.findByIdAndOwnerId(secondId, "owner-a")).thenReturn(Optional.of(second));
        when(datasetClient.catalog(versionId, "caller-token")).thenReturn(catalogWithColumns(
                "READY", 40, "amount", "category", "label"));
        UUID secondDatasetId = UUID.randomUUID();
        UUID secondVersionId = UUID.randomUUID();
        UUID secondTableId = UUID.randomUUID();
        when(datasetClient.catalog(secondVersionId, "caller-token")).thenReturn(
                new DatasetCatalog(secondDatasetId, secondVersionId, 1, "READY", "manifest", "schema",
                        List.of(new CatalogTable(secondTableId, "other::table", "Other table", 0, 30, 3,
                                java.util.Arrays.stream(new String[] {"label", "category", "amount"})
                                        .map(name -> new CatalogColumn(UUID.randomUUID(), name, 0, "STRING", true))
                                        .toList()))));
        when(datasetClient.rows(eq(versionId), eq(tableId), eq(40L), any(), eq("caller-token")))
                .thenReturn(List.of(List.of("12.50", "A", "yes")));
        when(datasetClient.rows(eq(secondVersionId), eq(secondTableId), eq(30L), any(), eq("caller-token")))
                .thenReturn(List.of(List.of("8.25", "B", "no")));
        when(pythonClient.trial(eq("owner-a"), any(), any(), eq("label")))
                .thenReturn(new TrialResult("classification", "label", 40, 40, 0,
                        Map.of("accuracy", 0.9), null, List.of()));
        when(trialRepository.save(any(ModelTrial.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var results = service.compare("owner-a", "caller-token", new ModelTrialCompareRequest(
                List.of(new ModelTrialTestSource(datasetId, versionId, tableId),
                        new ModelTrialTestSource(secondDatasetId, secondVersionId, secondTableId)),
                List.of(modelId, secondId), "label"));

        assertEquals(List.of(modelId, secondId, modelId, secondId),
                results.stream().map(result -> result.modelId()).toList());
        assertEquals(List.of(datasetId, datasetId, secondDatasetId, secondDatasetId),
                results.stream().map(result -> result.datasetId()).toList());
        assertEquals(1, results.stream().map(result -> result.comparisonId()).distinct().count());
        verify(datasetClient).rows(eq(versionId), eq(tableId), eq(40L), any(), eq("caller-token"));
        verify(datasetClient).rows(eq(secondVersionId), eq(secondTableId), eq(30L), any(), eq("caller-token"));
        ArgumentCaptor<ModelTrial> saved = ArgumentCaptor.forClass(ModelTrial.class);
        verify(trialRepository, org.mockito.Mockito.times(4)).save(saved.capture());
        assertEquals(List.of(modelId, secondId, modelId, secondId), saved.getAllValues().stream()
                .map(ModelTrial::getModelId).toList());
        ArgumentCaptor<List<Map<String, Object>>> firstRows = ArgumentCaptor.forClass(List.class);
        verify(pythonClient, org.mockito.Mockito.times(2))
                .trial(eq("owner-a"), eq("artifact-1"), firstRows.capture(), eq("label"));
        assertEquals(new java.math.BigDecimal("12.50"), firstRows.getAllValues().get(0).get(0).get("amount"));
        assertEquals(new java.math.BigDecimal("8.25"), firstRows.getAllValues().get(1).get(0).get("amount"));
        ArgumentCaptor<List<Map<String, Object>>> secondRows = ArgumentCaptor.forClass(List.class);
        verify(pythonClient, org.mockito.Mockito.times(2))
                .trial(eq("owner-a"), eq("artifact-2"), secondRows.capture(), eq("label"));
        assertEquals("A", secondRows.getAllValues().get(0).get(0).get("category"));
        assertEquals(new java.math.BigDecimal("12.50"), secondRows.getAllValues().get(0).get(0).get("amount"));
        assertEquals("B", secondRows.getAllValues().get(1).get(0).get("category"));
        assertEquals(new java.math.BigDecimal("8.25"), secondRows.getAllValues().get(1).get(0).get("amount"));
    }

    @Test
    void rejectsComparisonAcrossDifferentModelTasksBeforeReadingTestData() {
        UUID secondId = UUID.randomUUID();
        ModelVersion regression = new ModelVersion(secondId, "owner-a", "Amount model", "regression",
                "ridge_regression", UUID.randomUUID(), UUID.randomUUID(), 1, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 84, "{}", 32, 8, 0,
                "artifact-2", null, "[]", Instant.now());
        when(modelRepository.findByIdAndOwnerId(modelId, "owner-a")).thenReturn(Optional.of(model()));
        when(modelRepository.findByIdAndOwnerId(secondId, "owner-a")).thenReturn(Optional.of(regression));

        assertThrows(ResponseStatusException.class, () -> service.compare(
                "owner-a", "caller-token", new ModelTrialCompareRequest(
                        List.of(new ModelTrialTestSource(datasetId, versionId, tableId)),
                        List.of(modelId, secondId), "label")));

        verify(datasetClient, never()).catalog(any(), any());
        verify(datasetClient, never()).rows(any(), any(), anyLong(), any(), any());
        verify(pythonClient, never()).trial(any(), any(), any(), any());
    }

    private ModelVersion model() {
        return new ModelVersion(modelId, "owner-a", "Revenue model", "classification", "random_forest",
                UUID.randomUUID(), UUID.randomUUID(), 1, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 42, "{}", 32, 8, 0,
                "artifact-1", null, "[]", Instant.now());
    }

    private DatasetCatalog catalog(String status, long rowCount, String... names) {
        String[] columns = names.length == 0 ? new String[] {"amount", "label"} : names;
        List<CatalogColumn> catalogColumns = java.util.Arrays.stream(columns)
                .map(name -> new CatalogColumn(UUID.randomUUID(), name, 0, "STRING", true)).toList();
        return new DatasetCatalog(datasetId, versionId, 2, status, "manifest", "schema",
                List.of(new CatalogTable(tableId, "test::table", "Test table", 0, rowCount,
                        catalogColumns.size(), catalogColumns)));
    }

    private DatasetCatalog catalogWithColumns(String status, long rowCount, String... names) {
        List<CatalogColumn> catalogColumns = java.util.Arrays.stream(names)
                .map(name -> new CatalogColumn(UUID.randomUUID(), name, 0, "STRING", true)).toList();
        return new DatasetCatalog(datasetId, versionId, 2, status, "manifest", "schema",
                List.of(new CatalogTable(tableId, "test::table", "Test table", 0, rowCount,
                        catalogColumns.size(), catalogColumns)));
    }
}
