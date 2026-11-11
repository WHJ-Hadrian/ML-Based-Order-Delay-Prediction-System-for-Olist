package com.olist.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.olist.models.entity.ModelTrial;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.ModelVersionStatus;
import com.olist.models.entity.ModelWorkflow;
import com.olist.models.entity.TrainingRun;
import com.olist.models.repository.ModelTrialRepository;
import com.olist.models.repository.ModelVersionRepository;
import com.olist.models.repository.ModelWorkflowRepository;
import com.olist.models.repository.TrainingRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:model-management-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/olist",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/olist/protocol/openid-connect/certs",
            "olist.model-training.worker.enabled=false"
        })
class ModelManagementPersistenceTest {

    @Autowired
    private ModelVersionRepository repository;

    @Autowired
    private ModelTrialRepository trialRepository;

    @Autowired
    private ModelWorkflowRepository workflowRepository;

    @Autowired
    private TrainingRunRepository trainingRunRepository;

    @Test
    void migratesAndReopensOwnerScopedModelLineage() {
        UUID modelId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        ModelVersion model = new ModelVersion(
                modelId, "owner-a", "Revenue v1", "regression", "ridge_regression",
                datasetId, datasetVersionId, 4, tableId, "revenue", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 11, "{\"mae\":1.5}",
                80, 20, 0, UUID.randomUUID().toString(), null, "[]", Instant.now());

        repository.saveAndFlush(model);

        var reopened = repository.findByIdAndOwnerId(modelId, "owner-a").orElseThrow();
        assertEquals(datasetVersionId, reopened.getDatasetVersionId());
        assertEquals("{\"mae\":1.5}", reopened.getMetricsJson());
        assertEquals(ModelVersionStatus.DRAFT, reopened.getStatus());
        assertEquals("Revenue v1", reopened.getModelFamily());
        assertEquals(1, reopened.getVersionNo());
        assertTrue(repository.findByIdAndOwnerId(modelId, "owner-b").isEmpty());
    }

    @Test
    void migratesTrainingRunsAndAppliesModelLifecycleTransitions() {
        UUID runId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        TrainingRun run = new TrainingRun(
                runId, "owner-a", datasetId, datasetVersionId, tableId, "Revenue classifier",
                "classification", "random_forest", "label", "[\"amount\"]", 0.2, 42,
                "idem-a", "{\"name\":\"Revenue classifier\"}", "caller-token", Instant.now());

        trainingRunRepository.saveAndFlush(run);

        var reopenedRun = trainingRunRepository.findByIdAndOwnerId(runId, "owner-a").orElseThrow();
        assertEquals("QUEUED", reopenedRun.getStatus());
        assertEquals("QUEUED", reopenedRun.getStage());
        assertEquals(0, reopenedRun.getProgress());
        assertEquals("idem-a", reopenedRun.getIdempotencyKey());
        assertEquals("caller-token", reopenedRun.getOwnerToken());
        assertTrue(trainingRunRepository.findByIdAndOwnerId(runId, "owner-b").isEmpty());

        UUID modelId = UUID.randomUUID();
        ModelVersion model = new ModelVersion(
                modelId, "owner-a", "Revenue v2", "regression", "ridge_regression",
                datasetId, datasetVersionId, 5, tableId, "revenue", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 11, "{\"mae\":1.5}",
                80, 20, 0, UUID.randomUUID().toString(), null, "[]", Instant.now());
        repository.saveAndFlush(model);

        var reopenedModel = repository.findByIdAndOwnerId(modelId, "owner-a").orElseThrow();
        reopenedModel.approve("owner-a", Instant.now());
        repository.saveAndFlush(reopenedModel);
        assertEquals(ModelVersionStatus.APPROVED, reopenedModel.getStatus());
        assertEquals("owner-a", reopenedModel.getApprovedBy());
        assertNotNull(reopenedModel.getApprovedAt());

        reopenedModel.deprecate(Instant.now());
        repository.saveAndFlush(reopenedModel);
        assertEquals(ModelVersionStatus.DEPRECATED, reopenedModel.getStatus());
        assertNotNull(reopenedModel.getDeprecatedAt());
    }

    @Test
    void migratesAndReopensOwnerScopedModelTrials() {
        UUID modelId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        UUID datasetVersionId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        repository.saveAndFlush(new ModelVersion(
                modelId, "owner-a", "Revenue v1", "regression", "ridge_regression",
                UUID.randomUUID(), UUID.randomUUID(), 3, UUID.randomUUID(), "revenue", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 11, "{\"mae\":1.5}",
                80, 20, 0, UUID.randomUUID().toString(), null, "[]", Instant.now()));
        ModelTrial trial = new ModelTrial(
                UUID.randomUUID(), comparisonId, "owner-a", modelId, datasetId, datasetVersionId, 7,
                tableId, "revenue", 50, 48, 2, "{\"mae\":2.1}", null,
                "[{\"row\":1}]", Instant.now());
        ModelTrial peer = new ModelTrial(
                UUID.randomUUID(), comparisonId, "owner-a", modelId, datasetId, datasetVersionId, 7,
                tableId, "revenue", 50, 48, 2, "{\"mae\":2.4}", null,
                "[{\"row\":1}]", Instant.now());

        trialRepository.saveAndFlush(trial);
        trialRepository.saveAndFlush(peer);

        var reopened = trialRepository.findByIdAndOwnerId(trial.getId(), "owner-a").orElseThrow();
        assertEquals(datasetVersionId, reopened.getDatasetVersionId());
        assertEquals(comparisonId, reopened.getComparisonId());
        assertEquals("{\"mae\":2.1}", reopened.getMetricsJson());
        assertTrue(trialRepository.findByIdAndOwnerId(trial.getId(), "owner-b").isEmpty());
        assertEquals(2, trialRepository.findAllByComparisonIdAndOwnerIdOrderByCreatedAtAsc(
                comparisonId, "owner-a").size());
        assertTrue(trialRepository.findAllByComparisonIdAndOwnerIdOrderByCreatedAtAsc(
                comparisonId, "owner-b").isEmpty());
    }

    @Test
    void migratesAndReopensOwnerScopedWorkflowDocuments() {
        UUID workflowId = UUID.randomUUID();
        Instant now = Instant.now();
        workflowRepository.saveAndFlush(new ModelWorkflow(workflowId, "owner-a", "Forecast", 4,
                "{\"nodes\":[{\"id\":\"source\",\"type\":\"DATA_SOURCE\"}],\"edges\":[]}", now, now));

        var reopened = workflowRepository.findByIdAndOwnerId(workflowId, "owner-a").orElseThrow();
        assertEquals(4, reopened.getRevision());
        assertTrue(reopened.getDocumentJson().contains("DATA_SOURCE"));
        assertTrue(workflowRepository.findByIdAndOwnerId(workflowId, "owner-b").isEmpty());
    }
}