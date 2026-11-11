package com.olist.models.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.io.ByteArrayOutputStream;
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
        TrainingResult result = trainingResult();
        when(pythonClient.train(eq("user-1"), any(), any())).thenReturn(result);
        when(repository.save(any(ModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.train("user-1", "caller-token", request());

        assertEquals(datasetId, response.datasetId());
        assertEquals(versionId, response.datasetVersionId());
        assertEquals("artifact-1", response.artifactId());
        assertEquals(0.75, response.metrics().get("accuracy"));
        assertEquals("DRAFT", response.status());
        assertEquals("Revenue classifier", response.modelFamily());
        assertEquals(1, response.versionNo());
        ArgumentCaptor<PythonTrainingClient.DataSource> dataSource =
                ArgumentCaptor.forClass(PythonTrainingClient.DataSource.class);
        verify(pythonClient).train(eq("user-1"), any(), dataSource.capture());
        assertEquals(versionId, dataSource.getValue().datasetVersionId());
        assertEquals(tableId, dataSource.getValue().tableId());
        assertEquals("user-1", dataSource.getValue().ownerId());
        assertEquals(List.of("amount", "label"), dataSource.getValue().columns());
        ArgumentCaptor<ModelVersion> stored = ArgumentCaptor.forClass(ModelVersion.class);
        verify(repository).save(stored.capture());
        assertEquals("user-1", stored.getValue().getOwnerId());
        assertEquals(versionId, stored.getValue().getDatasetVersionId());
    }

    @Test
    void exportsTemporalSplitColumnWithoutUsingItAsFeature() {
        CatalogColumn createdAt = new CatalogColumn(UUID.randomUUID(), "created_at", 2, "TIMESTAMP", false);
        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(datasetId, versionId, "READY", 1_200, tableId, List.of(
                        new CatalogColumn(UUID.randomUUID(), "amount", 0, "STRING", true),
                        new CatalogColumn(UUID.randomUUID(), "label", 1, "STRING", false),
                        createdAt)));
        when(pythonClient.train(eq("user-1"), any(), any())).thenReturn(trainingResult());
        when(repository.save(any(ModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ModelTrainingRequest request = new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount"), 0.2, 0.0, 42, "temporal", "created_at", null, true);
        service.train("user-1", "caller-token", request);

        ArgumentCaptor<PythonTrainingClient.DataSource> dataSource =
                ArgumentCaptor.forClass(PythonTrainingClient.DataSource.class);
        verify(pythonClient).train(eq("user-1"), eq(request), dataSource.capture());
        assertEquals(List.of("amount", "label", "created_at"), dataSource.getValue().columns());
    }

    @Test
    void exportsGroupSplitColumnWithoutUsingItAsFeature() {
        CatalogColumn customerId = new CatalogColumn(UUID.randomUUID(), "customer_id", 2, "STRING", false);
        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(datasetId, versionId, "READY", 1_200, tableId, List.of(
                        new CatalogColumn(UUID.randomUUID(), "amount", 0, "STRING", true),
                        new CatalogColumn(UUID.randomUUID(), "label", 1, "STRING", false),
                        customerId)));
        when(pythonClient.train(eq("user-1"), any(), any())).thenReturn(trainingResult());
        when(repository.save(any(ModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ModelTrainingRequest request = new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount"), 0.2, 0.0, 42, "group", null, "customer_id", true);
        service.train("user-1", "caller-token", request);

        ArgumentCaptor<PythonTrainingClient.DataSource> dataSource =
                ArgumentCaptor.forClass(PythonTrainingClient.DataSource.class);
        verify(pythonClient).train(eq("user-1"), eq(request), dataSource.capture());
        assertEquals(List.of("amount", "label", "customer_id"), dataSource.getValue().columns());
    }

    @Test
    void rejectsSplitColumnsThatLeakIntoFeatures() {
        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(datasetId, versionId, "READY", 1_200, tableId, List.of(
                        new CatalogColumn(UUID.randomUUID(), "amount", 0, "STRING", true),
                        new CatalogColumn(UUID.randomUUID(), "label", 1, "STRING", false),
                        new CatalogColumn(UUID.randomUUID(), "created_at", 2, "TIMESTAMP", false),
                        new CatalogColumn(UUID.randomUUID(), "customer_id", 3, "STRING", false))));

        ModelTrainingRequest temporal = new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount", "created_at"), 0.2, 0.0, 42, "temporal", "created_at", null, true);
        ModelTrainingRequest group = new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount", "customer_id"), 0.2, 0.0, 42, "group", null, "customer_id", true);

        assertThrows(ResponseStatusException.class, () -> service.train("user-1", "caller-token", temporal));
        assertThrows(ResponseStatusException.class, () -> service.train("user-1", "caller-token", group));
        verify(pythonClient, never()).train(any(), any(), any());
    }

    @Test
    void rejectsDatasetVersionNotOwnedByRequestedDatasetBeforeReadingRows() {
        when(datasetClient.catalog(versionId, "caller-token"))
                .thenReturn(catalog(UUID.randomUUID(), versionId, "READY", 20, tableId));

        assertThrows(ResponseStatusException.class,
                () -> service.train("user-1", "caller-token", request()));

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

    @Test
    void approvesThenDeprecatesModelAndRejectsIllegalTransitions() {
        UUID modelId = UUID.randomUUID();
        ModelVersion model = model(modelId);
        when(repository.findByIdAndOwnerId(modelId, "user-1")).thenReturn(Optional.of(model));
        when(repository.save(any(ModelVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var approved = service.approve("user-1", modelId);
        assertEquals("APPROVED", approved.status());
        assertEquals("user-1", approved.approvedBy());
        assertNotNull(approved.approvedAt());
        assertThrows(ResponseStatusException.class, () -> service.approve("user-1", modelId));

        var deprecated = service.deprecate("user-1", modelId);
        assertEquals("DEPRECATED", deprecated.status());
        assertNotNull(deprecated.deprecatedAt());
        assertThrows(ResponseStatusException.class, () -> service.deprecate("user-1", modelId));
    }

    @Test
    void rejectsDeprecatingDraftAndEnforcesOwnerIsolation() {
        UUID modelId = UUID.randomUUID();
        ModelVersion draft = model(modelId);
        when(repository.findByIdAndOwnerId(modelId, "user-1")).thenReturn(Optional.of(draft));
        when(repository.findByIdAndOwnerId(modelId, "user-b")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.deprecate("user-1", modelId));
        assertThrows(ResponseStatusException.class, () -> service.approve("user-b", modelId));
        verify(repository, never()).save(any(ModelVersion.class));
    }

    @Test
    void proxiesManifestAndBundleForOwnedModel() {
        UUID modelId = UUID.randomUUID();
        ModelVersion model = model(modelId);
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        when(repository.findByIdAndOwnerId(modelId, "user-1")).thenReturn(Optional.of(model));
        when(pythonClient.manifest("user-1", model.getArtifactId())).thenReturn(Map.of("schemaVersion", 2));

        var manifest = service.manifest("user-1", modelId);
        service.streamArtifact("user-1", modelId, bundle);

        assertEquals(2, manifest.get("schemaVersion"));
        verify(pythonClient).manifest("user-1", model.getArtifactId());
        verify(pythonClient).streamArtifact("user-1", model.getArtifactId(), bundle);
    }

    @Test
    void rejectsArtifactProxyForAnotherOwner() {
        UUID modelId = UUID.randomUUID();
        ByteArrayOutputStream bundle = new ByteArrayOutputStream();
        when(repository.findByIdAndOwnerId(modelId, "user-2")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.manifest("user-2", modelId));
        assertThrows(ResponseStatusException.class, () -> service.streamArtifact("user-2", modelId, bundle));
        verify(pythonClient, never()).manifest(anyString(), anyString());
        verify(pythonClient, never()).streamArtifact(anyString(), anyString(), any());
    }

    private ModelTrainingRequest request() {
        return new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount"), 0.2, 0.0, 42, "random", null, null, true);
    }

    private ModelVersion model(UUID id) {
        return new ModelVersion(
                id, "user-1", "Revenue classifier", "classification", "random_forest",
                datasetId, versionId, 3, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 42, "{\"accuracy\":0.75}", 1_000, 200, 0,
                UUID.randomUUID().toString(), null, "[]", Instant.now());
    }

    private DatasetCatalog catalog(
            UUID expectedDatasetId, UUID expectedVersionId, String status, long rows, UUID expectedTableId) {
        return catalog(expectedDatasetId, expectedVersionId, status, rows, expectedTableId, List.of(
                new CatalogColumn(UUID.randomUUID(), "amount", 0, "STRING", true),
                new CatalogColumn(UUID.randomUUID(), "label", 1, "STRING", false)));
    }

    private DatasetCatalog catalog(
            UUID expectedDatasetId, UUID expectedVersionId, String status, long rows, UUID expectedTableId,
            List<CatalogColumn> columns) {
        return new DatasetCatalog(expectedDatasetId, expectedVersionId, 3, status, "manifest", "schema",
                List.of(new CatalogTable(expectedTableId, "source::table", "Orders", 0, rows, 2,
                        columns)));
    }

    private TrainingResult trainingResult() {
        return new TrainingResult(
                "artifact-1", datasetId, versionId, tableId, "classification", "random_forest", "label",
                List.of("amount"), Map.of("amount", "numeric"), 0.2, 42, Map.of("accuracy", 0.75),
                1_000, 200, 0, Map.of("labels", List.of("no", "yes")), List.of(),
                2, "fingerprint", Map.of("strategy", "random"), Map.of("sampleCount", 200),
                Map.of(), Map.of(), 0, Map.of("method", "permutation_importance"), 1.2, 100L, "sha");
    }
}
