package com.olist.models.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelPredictionRequest;
import com.olist.models.api.ModelPredictionResponse;
import com.olist.models.client.PythonTrainingClient;
import com.olist.models.entity.ModelPrediction;
import com.olist.models.entity.ModelVersion;
import com.olist.models.repository.ModelPredictionRepository;
import com.olist.models.repository.ModelVersionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 单笔预测的持久化队列：提交后立即入库并返回，后台定时任务依次执行，
 * 进程重启后 QUEUED 任务会继续执行，历史记录对所有浏览器可见。
 */
@Service
public class ModelPredictionService {

    private static final Logger log = LoggerFactory.getLogger(ModelPredictionService.class);
    private static final int MAX_HISTORY = 50;

    private final ModelPredictionRepository predictionRepository;
    private final ModelVersionRepository modelRepository;
    private final PythonTrainingClient pythonClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public ModelPredictionService(
            ModelPredictionRepository predictionRepository,
            ModelVersionRepository modelRepository,
            PythonTrainingClient pythonClient,
            ObjectMapper objectMapper) {
        this.predictionRepository = predictionRepository;
        this.modelRepository = modelRepository;
        this.pythonClient = pythonClient;
        this.objectMapper = objectMapper;
    }

    public ModelPredictionResponse submit(String ownerId, ModelPredictionRequest request) {
        ModelVersion model = modelRepository.findByIdAndOwnerId(request.modelId(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
        if (model.getArtifactId() == null || model.getArtifactId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该模型版本还没有可用的训练产物");
        }
        Set<String> features = new LinkedHashSet<>(readList(model.getFeatureColumnsJson()));
        List<String> unknown = request.features().keySet().stream()
                .filter(name -> !features.contains(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "以下字段不属于该模型：" + String.join("、", unknown));
        }
        ModelPrediction task = predictionRepository.save(new ModelPrediction(
                UUID.randomUUID(), ownerId, model.getId(), model.getName(),
                json(request.features()), Boolean.TRUE.equals(request.includeCounterfactuals()), Instant.now()));
        return toResponse(task);
    }

    public List<ModelPredictionResponse> list(String ownerId) {
        return predictionRepository.findTop50ByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .limit(MAX_HISTORY)
                .map(this::toResponse)
                .toList();
    }

    public ModelPredictionResponse get(String ownerId, UUID id) {
        return toResponse(requireOwned(ownerId, id));
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(String ownerId, UUID id) {
        if (predictionRepository.deleteByIdAndOwnerId(id, ownerId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预测记录不存在");
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public long clear(String ownerId) {
        return predictionRepository.deleteByOwnerId(ownerId);
    }

    /** 进程重启后把残留的 RUNNING 任务放回队列，避免永远卡住。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        List<ModelPrediction> stale = predictionRepository.findByStatus(ModelPrediction.RUNNING);
        for (ModelPrediction task : stale) {
            task.requeue();
        }
        if (!stale.isEmpty()) {
            predictionRepository.saveAll(stale);
            log.info("Requeued {} interrupted prediction task(s)", stale.size());
        }
    }

    @Scheduled(fixedDelayString = "${olist.model-prediction.poll-millis:2000}")
    public void pollQueue() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            ModelPrediction task = predictionRepository.findFirstByStatusOrderByCreatedAtAsc(ModelPrediction.QUEUED)
                    .orElse(null);
            if (task == null) {
                return;
            }
            task.markRunning(Instant.now());
            predictionRepository.save(task);
            execute(task);
        } catch (Exception error) {
            log.warn("Prediction queue poll failed: {}", error.getMessage());
        } finally {
            polling.set(false);
        }
    }

    private void execute(ModelPrediction task) {
        try {
            ModelVersion model = modelRepository.findByIdAndOwnerId(task.getModelId(), task.getOwnerId())
                    .orElseThrow(() -> new IllegalStateException("模型版本不存在"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ownerId", task.getOwnerId());
            payload.put("artifactId", model.getArtifactId());
            payload.put("rows", List.of(readMap(task.getInputJson())));
            payload.put("includeCounterfactuals", task.isIncludeCounterfactuals());
            Map<String, Object> response = pythonClient.explainModel(payload);
            Map<String, Object> explanation = firstResult(response);
            task.succeed(
                    Instant.now(),
                    json(explanation),
                    stringValue(explanation.get("prediction")),
                    doubleValue(explanation.get("probability")),
                    doubleValue(explanation.get("threshold")));
        } catch (Exception error) {
            task.fail(Instant.now(), error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        predictionRepository.save(task);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstResult(Map<String, Object> response) {
        Object results = response.get("results");
        if (results instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return (Map<String, Object>) first;
        }
        throw new IllegalStateException("解释服务没有返回结果");
    }

    private ModelPrediction requireOwned(String ownerId, UUID id) {
        return predictionRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "预测记录不存在"));
    }

    private ModelPredictionResponse toResponse(ModelPrediction task) {
        return new ModelPredictionResponse(
                task.getId(), task.getModelId(), task.getModelName(), task.getStatus(),
                readMap(task.getInputJson()), readMap(task.getResultJson()),
                task.getErrorMessage(), task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt());
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException error) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化预测数据", error);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
