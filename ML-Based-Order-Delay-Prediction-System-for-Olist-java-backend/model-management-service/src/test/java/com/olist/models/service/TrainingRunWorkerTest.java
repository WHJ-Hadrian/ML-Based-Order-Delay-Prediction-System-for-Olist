package com.olist.models.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrainingRequest;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.TrainingRun;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TrainingRunWorkerTest {

    @Mock
    private TrainingRunService runService;

    @Mock
    private ModelTrainingService trainingService;

    @Mock
    private TrainingTokenProvider tokenProvider;

    private ObjectMapper objectMapper;
    private TrainingRunWorker worker;
    private UUID datasetId;
    private UUID versionId;
    private UUID tableId;
    private ModelTrainingRequest request;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        worker = new TrainingRunWorker(runService, trainingService, tokenProvider, objectMapper, true, 5, 1800);
        datasetId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        tableId = UUID.randomUUID();
        request = new ModelTrainingRequest(
                datasetId, versionId, tableId, "Revenue classifier", "classification", "random_forest",
                "label", List.of("amount"), 0.2, 0.0, 42, "random", null, null, true);
    }

    @Test
    void executesClaimedRunAndRegistersModel() {
        TrainingRun run = run();
        ModelVersion model = model();
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));
        when(tokenProvider.resolve(run)).thenReturn("worker-token");
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_TRAINING, 30)).thenReturn(true);
        when(trainingService.execute("owner-a", "worker-token", request)).thenReturn(model);
        when(runService.isCancelled(run.getId())).thenReturn(false);
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_REGISTERING, 90)).thenReturn(true);

        int processed = worker.runOnce();

        assertEquals(1, processed);
        verify(runService).complete(eq(run.getId()), eq("claim-token"), eq(model), contains("modelVersionId"));
    }

    @Test
    void doesNotExecuteWhenRunWasCancelledBeforeTrainingStarts() {
        TrainingRun run = run();
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));
        when(tokenProvider.resolve(run)).thenReturn("worker-token");
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_TRAINING, 30)).thenReturn(false);

        int processed = worker.runOnce();

        assertEquals(1, processed);
        verify(trainingService, never()).execute(any(), any(), any());
        verify(runService, never()).complete(any(), anyString(), any(), anyString());
    }

    @Test
    void doesNotRegisterModelWhenCancelledDuringTraining() {
        TrainingRun run = run();
        ModelVersion model = model();
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));
        when(tokenProvider.resolve(run)).thenReturn("worker-token");
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_TRAINING, 30)).thenReturn(true);
        when(trainingService.execute("owner-a", "worker-token", request)).thenReturn(model);
        when(runService.isCancelled(run.getId())).thenReturn(true);

        worker.runOnce();

        verify(runService, never()).complete(any(), anyString(), any(), anyString());
    }

    @Test
    void recordsFailureWhenTrainingEngineRejectsRequest() {
        TrainingRun run = run();
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));
        when(tokenProvider.resolve(run)).thenReturn("worker-token");
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_TRAINING, 30)).thenReturn(true);
        when(trainingService.execute("owner-a", "worker-token", request))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid data"));

        worker.runOnce();

        verify(runService).fail(run.getId(), "claim-token", "invalid data");
    }

    @Test
    void recordsFailureWhenWorkerTokenIsMissing() {
        TrainingRun run = run();
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));
        when(tokenProvider.resolve(run)).thenThrow(new IllegalStateException("missing token"));

        worker.runOnce();

        verify(runService).fail(run.getId(), "claim-token", "missing token");
        verify(trainingService, never()).execute(any(), any(), any());
        worker.heartbeat();
        verify(runService, never()).renewLease(any(), anyString(), any(Duration.class));
    }

    @Test
    void heartbeatRenewsLeaseWhileTrainingIsInFlight() {
        TrainingRun run = run();
        ModelVersion model = model();
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));
        when(tokenProvider.resolve(run)).thenReturn("worker-token");
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_TRAINING, 30)).thenReturn(true);
        when(trainingService.execute("owner-a", "worker-token", request)).thenAnswer(invocation -> {
            worker.heartbeat();
            return model;
        });
        when(runService.isCancelled(run.getId())).thenReturn(false);
        when(runService.updateStage(run.getId(), "claim-token", TrainingRun.STAGE_REGISTERING, 90)).thenReturn(true);
        when(runService.renewLease(run.getId(), "claim-token", Duration.ofSeconds(1800))).thenReturn(true);

        worker.runOnce();

        verify(runService).renewLease(run.getId(), "claim-token", Duration.ofSeconds(1800));
        verify(runService).complete(eq(run.getId()), eq("claim-token"), eq(model), contains("modelVersionId"));
    }

    @Test
    void clearsActiveClaimWhenStoredRequestCannotBeParsed() {
        TrainingRun run = run("{");
        when(runService.claimBatch(anyInt(), any(Duration.class))).thenReturn(List.of(run));

        worker.runOnce();

        verify(runService).fail(eq(run.getId()), eq("claim-token"), contains("无法恢复训练请求"));
        verify(tokenProvider, never()).resolve(any());
        worker.heartbeat();
        verify(runService, never()).renewLease(any(), anyString(), any(Duration.class));
    }

    private TrainingRun run() {
        return run(requestJson());
    }

    private TrainingRun run(String storedRequestJson) {
        TrainingRun run = new TrainingRun(
                UUID.randomUUID(), "owner-a", datasetId, versionId, tableId, "Revenue classifier",
                "classification", "random_forest", "label", "[\"amount\"]", 0.2, 42,
                "idem-1", storedRequestJson, "caller-token", Instant.now());
        run.claim("claim-token", Instant.now(), Instant.now().plusSeconds(60));
        return run;
    }

    private String requestJson() {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private ModelVersion model() {
        return new ModelVersion(
                UUID.randomUUID(), "owner-a", "Revenue classifier", "classification", "random_forest",
                datasetId, versionId, 3, tableId, "label", "[\"amount\"]",
                "{\"amount\":\"numeric\"}", 0.2, 42, "{\"accuracy\":0.75}", 1_000, 200, 0,
                UUID.randomUUID().toString(), null, "[]", Instant.now());
    }
}
