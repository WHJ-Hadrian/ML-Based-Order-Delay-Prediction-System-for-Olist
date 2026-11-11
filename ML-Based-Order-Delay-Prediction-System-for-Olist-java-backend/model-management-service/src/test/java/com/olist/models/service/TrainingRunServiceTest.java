package com.olist.models.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrainingRequest;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.TrainingRun;
import com.olist.models.repository.ModelVersionRepository;
import com.olist.models.repository.TrainingRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
class TrainingRunServiceTest {

    @Mock
    private TrainingRunRepository repository;

    @Mock
    private ModelVersionRepository modelVersionRepository;

    private TrainingRunService service;
    private ObjectMapper objectMapper;
    private UUID datasetId;
    private UUID versionId;
    private UUID tableId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TrainingRunService(repository, modelVersionRepository, objectMapper, 3);
        datasetId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        tableId = UUID.randomUUID();
    }

    @Test
    void submitsQueuedRunAndPersistsIdempotencyKey() {
        when(repository.save(any(TrainingRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.submit("owner-a", "caller-token", request(), "  idem-1  ");

        assertEquals("QUEUED", response.status());
        assertEquals("QUEUED", response.stage());
        assertEquals(0, response.progress());
        assertEquals("/api/v1/models/training-runs/" + response.id(), response.statusUrl());
        ArgumentCaptor<TrainingRun> stored = ArgumentCaptor.forClass(TrainingRun.class);
        verify(repository).save(stored.capture());
        assertEquals("owner-a", stored.getValue().getOwnerId());
        assertEquals("idem-1", stored.getValue().getIdempotencyKey());
        assertEquals("caller-token", stored.getValue().getOwnerToken());
        assertTrue(stored.getValue().getRequestJson().contains("datasetVersionId"));
    }

    @Test
    void returnsExistingRunForSameIdempotencyKeyAndRequest() throws Exception {
        TrainingRun existing = run(objectMapper.writeValueAsString(request()));
        when(repository.findByOwnerIdAndIdempotencyKey("owner-a", "idem-1"))
                .thenReturn(Optional.of(existing));

        var response = service.submit("owner-a", "caller-token", request(), "idem-1");

        assertEquals(existing.getId(), response.id());
        assertEquals("QUEUED", response.status());
        verify(repository, never()).save(any(TrainingRun.class));
    }

    @Test
    void rejectsIdempotencyKeyReusedWithDifferentRequest() {
        TrainingRun existing = run("{}");
        when(repository.findByOwnerIdAndIdempotencyKey("owner-a", "idem-1"))
                .thenReturn(Optional.of(existing));

        assertThrows(ResponseStatusException.class,
                () -> service.submit("owner-a", "caller-token", request(), "idem-1"));
        verify(repository, never()).save(any(TrainingRun.class));
    }

    @Test
    void replaysExistingRunAfterConcurrentInsertConflict() throws Exception {
        TrainingRun existing = run(objectMapper.writeValueAsString(request()));
        when(repository.findByOwnerIdAndIdempotencyKey("owner-a", "idem-1"))
                .thenReturn(Optional.of(existing));

        var response = service.replay("owner-a", request(), " idem-1 ");

        assertEquals(existing.getId(), response.id());
        assertEquals("caller-token", existing.getOwnerToken());
        verify(repository, never()).save(any(TrainingRun.class));
    }

    @Test
    void replayRequiresIdempotencyKeyAndMatchingRequest() {
        assertThrows(ResponseStatusException.class, () -> service.replay("owner-a", request(), "  "));

        TrainingRun existing = run("{}");
        when(repository.findByOwnerIdAndIdempotencyKey("owner-a", "idem-1"))
                .thenReturn(Optional.of(existing));

        assertThrows(ResponseStatusException.class,
                () -> service.replay("owner-a", request(), "idem-1"));
        verify(repository, never()).save(any(TrainingRun.class));
    }

    @Test
    void cancelsQueuedRunAndRejectsSecondCancellation() {
        TrainingRun run = run(objectMapperJson());
        when(repository.lockByIdAndOwnerId(run.getId(), "owner-a")).thenReturn(Optional.of(run));
        when(repository.save(any(TrainingRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var cancelled = service.cancel("owner-a", run.getId());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", cancelled.stage());
        assertNotNull(cancelled.finishedAt());
        assertNull(run.getClaimToken());
        assertThrows(ResponseStatusException.class, () -> service.cancel("owner-a", run.getId()));
    }

    @Test
    void claimsQueuedRunWithLeaseAndClaimToken() {
        TrainingRun run = run(objectMapperJson());
        when(repository.findTop20ByStatusOrderByCreatedAtAsc("QUEUED")).thenReturn(List.of(run));
        when(repository.claimQueued(eq(run.getId()), anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    run.claim(invocation.getArgument(1), invocation.getArgument(3), invocation.getArgument(2));
                    return 1;
                });
        when(repository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq("RUNNING"), any(Instant.class))).thenReturn(List.of());
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));

        List<TrainingRun> claimed = service.claimBatch(5, Duration.ofSeconds(60));

        assertEquals(1, claimed.size());
        assertEquals("RUNNING", claimed.get(0).getStatus());
        assertEquals("VALIDATING", claimed.get(0).getStage());
        assertEquals(1, claimed.get(0).getAttempts());
        assertNotNull(claimed.get(0).getClaimToken());
        assertNotNull(claimed.get(0).getLeaseExpiresAt());
    }

    @Test
    void reclaimsExpiredLeaseAndFailsAfterMaxAttempts() {
        Instant now = Instant.now();
        TrainingRun expired = run(objectMapperJson());
        expired.claim("old-token", now.minusSeconds(120), now.minusSeconds(60));
        when(repository.findTop20ByStatusOrderByCreatedAtAsc("QUEUED")).thenReturn(List.of());
        when(repository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq("RUNNING"), any(Instant.class))).thenReturn(List.of(expired));
        when(repository.reclaimExpired(eq(expired.getId()), anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    expired.claim(invocation.getArgument(1), invocation.getArgument(3), invocation.getArgument(2));
                    return 1;
                });
        when(repository.findById(expired.getId())).thenReturn(Optional.of(expired));

        List<TrainingRun> claimed = service.claimBatch(5, Duration.ofSeconds(60));

        assertEquals(1, claimed.size());
        assertEquals(2, expired.getAttempts());
        assertNotEquals("old-token", expired.getClaimToken());

        TrainingRun exhausted = run(objectMapperJson());
        exhausted.claim("a", now.minusSeconds(120), now.minusSeconds(60));
        exhausted.claim("b", now.minusSeconds(120), now.minusSeconds(60));
        exhausted.claim("c", now.minusSeconds(120), now.minusSeconds(60));
        when(repository.findTop20ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq("RUNNING"), any(Instant.class))).thenReturn(List.of(exhausted));
        when(repository.failExpired(eq(exhausted.getId()), anyString(), any(Instant.class))).thenReturn(1);

        List<TrainingRun> exhaustedClaims = service.claimBatch(5, Duration.ofSeconds(60));

        assertTrue(exhaustedClaims.isEmpty());
        verify(repository).failExpired(eq(exhausted.getId()), anyString(), any(Instant.class));
    }

    @Test
    void completesRunningClaimAndPersistsModelVersion() {
        TrainingRun run = run(objectMapperJson());
        run.claim("claim-token", Instant.now(), Instant.now().plusSeconds(60));
        ModelVersion model = model();
        when(repository.lockById(run.getId())).thenReturn(Optional.of(run));
        when(modelVersionRepository.save(model)).thenReturn(model);
        when(repository.save(run)).thenReturn(run);

        var response = service.complete(run.getId(), "claim-token", model, "{\"ok\":true}");

        assertEquals("SUCCEEDED", response.status());
        assertEquals("COMPLETED", response.stage());
        assertEquals(100, response.progress());
        assertEquals(model.getId(), response.modelVersionId());
        assertEquals(model.getArtifactId(), response.artifactId());
        assertEquals(Boolean.TRUE, response.result().get("ok"));
        assertNull(run.getOwnerToken());
        assertNull(run.getClaimToken());
    }

    @Test
    void refusesToCompleteCancelledRun() {
        TrainingRun run = run(objectMapperJson());
        run.claim("claim-token", Instant.now(), Instant.now().plusSeconds(60));
        run.cancel(Instant.now());
        when(repository.lockById(run.getId())).thenReturn(Optional.of(run));

        assertThrows(ResponseStatusException.class,
                () -> service.complete(run.getId(), "claim-token", model(), "{}"));
        verify(modelVersionRepository, never()).save(any(ModelVersion.class));
    }

    @Test
    void failDoesNotOverwriteCancelledRun() {
        TrainingRun run = run(objectMapperJson());
        run.cancel(Instant.now());
        when(repository.lockById(run.getId())).thenReturn(Optional.of(run));

        service.fail(run.getId(), "claim-token", "boom");

        verify(repository, never()).save(any(TrainingRun.class));
    }

    private ModelTrainingRequest request() {
        return new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount"), 0.2, 0.0, 42, "random", null, null, true);
    }

    private String objectMapperJson() {
        try {
            return objectMapper.writeValueAsString(request());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private TrainingRun run(String requestJson) {
        Instant now = Instant.now();
        return new TrainingRun(
                UUID.randomUUID(), "owner-a", datasetId, versionId, tableId, "Revenue classifier",
                "classification", "random_forest", "label", "[\"amount\"]", 0.2, 42,
                "idem-1", requestJson, "caller-token", now);
    }

    private ModelVersion model() {
        return new ModelVersion(
                UUID.randomUUID(), "owner-a", "Revenue classifier", "classification", "random_forest",
                datasetId, versionId, 3, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 42, "{\"accuracy\":0.75}", 1_000, 200, 0,
                UUID.randomUUID().toString(), null, "[]", Instant.now());
    }
}
