package com.olist.models.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrainingRequest;
import com.olist.models.api.ModelVersionResponse;
import com.olist.models.client.PythonTrainingClient;
import com.olist.models.client.PythonTrainingClient.TrainingResult;
import com.olist.models.client.TrainingDatasetClient;
import com.olist.models.client.TrainingDatasetClient.CatalogTable;
import com.olist.models.client.TrainingDatasetClient.DatasetCatalog;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.ModelVersionStatus;
import com.olist.models.repository.ModelVersionRepository;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelTrainingService {

    private static final int MAX_TRAINING_ROWS = 100_000;
    private static final long MAX_TRAINING_CELLS = 2_000_000;
    private final TrainingDatasetClient datasetClient;
    private final PythonTrainingClient pythonClient;
    private final ModelVersionRepository repository;
    private final ObjectMapper objectMapper;

    public ModelTrainingService(
            TrainingDatasetClient datasetClient,
            PythonTrainingClient pythonClient,
            ModelVersionRepository repository,
            ObjectMapper objectMapper) {
        this.datasetClient = datasetClient;
        this.pythonClient = pythonClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ModelVersionResponse train(String ownerId, String bearerToken, ModelTrainingRequest request) {
        return toResponse(repository.save(execute(ownerId, bearerToken, request)));
    }

    public ModelVersion execute(String ownerId, String bearerToken, ModelTrainingRequest request) {
        DatasetCatalog catalog = datasetClient.catalog(request.datasetVersionId(), bearerToken);
        if (catalog == null
                || !request.datasetId().equals(catalog.datasetId())
                || !request.datasetVersionId().equals(catalog.datasetVersionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选数据集版本");
        }
        if (!"READY".equalsIgnoreCase(catalog.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能使用 READY 数据集版本训练模型");
        }
        CatalogTable table = catalog.tables().stream()
                .filter(candidate -> request.tableId().equals(candidate.tableId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选数据表"));
        validateConfiguration(request, table);
        List<String> selectedColumns = dataColumns(request);
        PythonTrainingClient.DataSource dataSource = new PythonTrainingClient.DataSource(
                catalog.datasetVersionId(), table.tableId(), ownerId, selectedColumns,
                (int) Math.min(table.rowCount(), MAX_TRAINING_ROWS));
        TrainingResult result = pythonClient.train(ownerId, request, dataSource);
        if (result == null || result.artifactId() == null || result.artifactId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "训练引擎没有返回有效模型产物");
        }

        String modelName = request.name() == null || request.name().isBlank()
                ? request.task() + " · 数据集版本 " + catalog.versionNo()
                : request.name().trim();
        int versionNo = (int) repository.countByOwnerIdAndModelFamily(ownerId, modelName) + 1;
        ModelVersion model = new ModelVersion(
                UUID.randomUUID(), ownerId, modelName, result.task(), result.algorithm(),
                catalog.datasetId(), catalog.datasetVersionId(), catalog.versionNo(), table.tableId(),
                result.targetColumn(), json(result.featureColumns()), json(result.featureTypes()),
                result.testSize(), result.randomState(), json(result.metrics()), result.trainRows(),
                result.testRows(), result.droppedTargetRows(), result.artifactId(),
                result.confusionMatrix() == null ? null : json(result.confusionMatrix()),
                json(result.evaluationSamples()), Instant.now());
        model.assignVersion(modelName, versionNo);
        return model;
    }

    @Transactional(readOnly = true)
    public List<ModelVersionResponse> list(String ownerId) {
        return repository.findTop100ByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ModelVersionResponse get(String ownerId, UUID id) {
        ModelVersion model = repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
        return toResponse(model);
    }

    @Transactional
    public ModelVersionResponse approve(String ownerId, UUID id) {
        ModelVersion model = requireOwnedModel(ownerId, id);
        if (model.getStatus() != ModelVersionStatus.DRAFT
                && model.getStatus() != ModelVersionStatus.CANDIDATE
                && model.getStatus() != ModelVersionStatus.VALIDATED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "模型版本当前状态不允许审批: " + model.getStatus());
        }
        model.approve(ownerId, Instant.now());
        return toResponse(repository.save(model));
    }

    @Transactional
    public ModelVersionResponse deprecate(String ownerId, UUID id) {
        ModelVersion model = requireOwnedModel(ownerId, id);
        if (model.getStatus() != ModelVersionStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "只有已审批模型版本可以弃用，当前状态: " + model.getStatus());
        }
        model.deprecate(Instant.now());
        return toResponse(repository.save(model));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> manifest(String ownerId, UUID id) {
        ModelVersion model = requireOwnedModel(ownerId, id);
        return pythonClient.manifest(ownerId, model.getArtifactId());
    }

    @Transactional(readOnly = true)
    public void streamArtifact(String ownerId, UUID id, OutputStream output) {
        ModelVersion model = requireOwnedModel(ownerId, id);
        pythonClient.streamArtifact(ownerId, model.getArtifactId(), output);
    }

    private ModelVersion requireOwnedModel(String ownerId, UUID id) {
        return repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
    }

    private void validateConfiguration(ModelTrainingRequest request, CatalogTable table) {
        if (table.rowCount() < 12 || table.rowCount() > MAX_TRAINING_ROWS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "训练数据需要 12 到 100,000 行，当前为 " + table.rowCount()
                            + " 行。请先在数据处理模块筛选或聚合，或生成一个不超过 100,000 行的数据版本。");
        }
        if (request.cvFolds() != 0 && request.cvFolds() != 3
                && request.cvFolds() != 5 && request.cvFolds() != 10) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "交叉验证折数只能是 0、3、5 或 10");
        }
        if (request.randomState() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "随机种子不能小于 0");
        }
        if (request.featureColumns().stream().distinct().count() != request.featureColumns().size()
                || request.featureColumns().contains(request.targetColumn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "特征字段不能重复，目标字段也不能出现在特征字段中");
        }
        if (request.testSize() + request.validationSize() > 0.6) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "测试集与验证集比例之和不能超过 0.6");
        }
        if (!List.of("random", "temporal", "group").contains(request.splitStrategy())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的切分策略");
        }
        if ("temporal".equals(request.splitStrategy())
                && (request.timeColumn() == null || request.timeColumn().isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "时间切分必须指定时间字段");
        }
        if ("group".equals(request.splitStrategy())
                && (request.groupColumn() == null || request.groupColumn().isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "分组切分必须指定分组字段");
        }
        if ("temporal".equals(request.splitStrategy())
                && request.featureColumns().contains(request.timeColumn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "时间字段不能同时作为输入特征");
        }
        if ("group".equals(request.splitStrategy())
                && request.featureColumns().contains(request.groupColumn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "分组字段不能同时作为输入特征，避免分组泄漏");
        }
        boolean compatible = switch (request.task()) {
            case "classification" -> List.of(
                    "random_forest", "logistic_regression", "decision_tree",
                    "xgboost", "lightgbm", "svm", "knn").contains(request.algorithm());
            case "regression" -> List.of(
                    "random_forest", "ridge_regression", "linear_regression",
                    "decision_tree", "xgboost_regression", "lightgbm_regression")
                    .contains(request.algorithm());
            default -> false;
        };
        if (!compatible) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "任务类型与算法不兼容");
        }
        List<String> available = table.columns().stream().map(column -> column.name()).toList();
        for (String selected : dataColumns(request)) {
            if (!available.contains(selected)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "字段不属于所选数据表: " + selected);
            }
        }
        if (table.rowCount() * (request.featureColumns().size() + 1L) > MAX_TRAINING_CELLS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "所选行数与字段数超过单次训练资源上限，请先缩小字段范围");
        }
    }

    private List<String> dataColumns(ModelTrainingRequest request) {
        List<String> columns = new ArrayList<>(request.featureColumns());
        columns.add(request.targetColumn());
        if ("temporal".equals(request.splitStrategy()) && request.timeColumn() != null) {
            columns.add(request.timeColumn());
        }
        if ("group".equals(request.splitStrategy()) && request.groupColumn() != null) {
            columns.add(request.groupColumn());
        }
        return columns.stream().distinct().toList();
    }

    private ModelVersionResponse toResponse(ModelVersion model) {
        return new ModelVersionResponse(
                model.getId(), model.getName(), model.getTask(), model.getAlgorithm(), model.getDatasetId(),
                model.getDatasetVersionId(), model.getDatasetVersionNo(), model.getTableId(),
                model.getTargetColumn(), read(model.getFeatureColumnsJson(), new TypeReference<>() {}),
                read(model.getFeatureTypesJson(), new TypeReference<>() {}), model.getTestSize(),
                model.getRandomState(), read(model.getMetricsJson(), new TypeReference<>() {}),
                model.getTrainRows(), model.getTestRows(), model.getDroppedTargetRows(), model.getArtifactId(),
                model.getConfusionMatrixJson() == null ? null
                        : read(model.getConfusionMatrixJson(), new TypeReference<>() {}),
                read(model.getEvaluationSamplesJson(), new TypeReference<>() {}), model.getCreatedAt(),
                model.getStatus() == null ? null : model.getStatus().name(),
                model.getModelFamily(), model.getVersionNo(), model.getApprovedAt(),
                model.getApprovedBy(), model.getDeprecatedAt());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化模型版本元数据", error);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("模型版本元数据已损坏", error);
        }
    }
}
