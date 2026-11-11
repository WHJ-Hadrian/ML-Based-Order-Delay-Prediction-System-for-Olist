package com.olist.models.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrainingRequest;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.TrainingRun;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TrainingRunWorker {

    private final TrainingRunService runService;
    private final ModelTrainingService trainingService;
    private final TrainingTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseSeconds;
    private final AtomicBoolean executing = new AtomicBoolean(false);
    private final Map<UUID, String> activeClaims = new ConcurrentHashMap<>();

    public TrainingRunWorker(
            TrainingRunService runService,
            ModelTrainingService trainingService,
            TrainingTokenProvider tokenProvider,
            ObjectMapper objectMapper,
            @Value("${olist.model-training.worker.enabled:true}") boolean enabled,
            @Value("${olist.model-training.worker.batch-size:5}") int batchSize,
            @Value("${olist.model-training.worker.lease-seconds:1800}") long leaseSeconds) {
        this.runService = runService;
        this.trainingService = trainingService;
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 20));
        this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${olist.model-training.worker.poll-interval-ms:5000}")
    public void poll() {
        if (!enabled || !executing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<TrainingRun> claimed = runService.claimBatch(batchSize, Duration.ofSeconds(leaseSeconds));
            for (TrainingRun run : claimed) {
                process(run);
            }
        } finally {
            executing.set(false);
        }
    }

    public int runOnce() {
        if (!enabled || !executing.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<TrainingRun> claimed = runService.claimBatch(batchSize, Duration.ofSeconds(leaseSeconds));
            for (TrainingRun run : claimed) {
                process(run);
            }
            return claimed.size();
        } finally {
            executing.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${olist.model-training.worker.heartbeat-interval-ms:60000}")
    public void heartbeat() {
        if (!enabled || activeClaims.isEmpty()) {
            return;
        }
        activeClaims.forEach((runId, claimToken) -> {
            if (!runService.renewLease(runId, claimToken, Duration.ofSeconds(leaseSeconds))) {
                activeClaims.remove(runId);
            }
        });
    }

    void process(TrainingRun run) {
        String claimToken = run.getClaimToken();
        activeClaims.put(run.getId(), claimToken);
        try {
            ModelTrainingRequest request;
            try {
                request = objectMapper.readValue(run.getRequestJson(), ModelTrainingRequest.class);
            } catch (Exception error) {
                runService.fail(run.getId(), claimToken, "无法恢复训练请求: " + error.getMessage());
                return;
            }

            String token;
            try {
                token = tokenProvider.resolve(run);
            } catch (RuntimeException error) {
                runService.fail(run.getId(), claimToken, error.getMessage());
                return;
            }

            try {
                if (!runService.updateStage(run.getId(), claimToken, TrainingRun.STAGE_TRAINING, 30)) {
                    return;
                }
                ModelVersion model = trainingService.execute(run.getOwnerId(), token, request);
                if (runService.isCancelled(run.getId())) {
                    return;
                }
                if (!runService.updateStage(run.getId(), claimToken, TrainingRun.STAGE_REGISTERING, 90)) {
                    return;
                }
                runService.complete(run.getId(), claimToken, model, resultJson(model));
            } catch (ResponseStatusException error) {
                runService.fail(run.getId(), claimToken,
                        error.getReason() == null ? error.getMessage() : error.getReason());
            } catch (Exception error) {
                runService.fail(run.getId(), claimToken, error.getMessage());
            }
        } finally {
            activeClaims.remove(run.getId());
        }
    }

    private String resultJson(ModelVersion model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelVersionId", model.getId());
        result.put("artifactId", model.getArtifactId());
        try {
            result.put("metrics", objectMapper.readTree(model.getMetricsJson()));
            return objectMapper.writeValueAsString(result);
        } catch (Exception ignored) {
            return null;
        }
    }
}
