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
import com.olist.models.repository.ModelVersionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        DatasetCatalog catalog = datasetClient.catalog(request.datasetVersionId(), bearerToken);
        if (catalog == null
                || !request.datasetId().equals(catalog.datasetId())
                || !request.datasetVersionId().equals(catalog.datasetVersionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所選数据集版本");
        }
        if (!"READY".equalsIgnoreCase(catalog.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能使用 READY 数据集版本训练模型");
        }
        CatalogTable table = catalog.tables().stream()
                .filter(candidate -> request.tableId().equals(candidate.tableId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选数据表"));
        validateConfiguration(request, table);
        List<String> selectedColumns = new ArrayList<>(request.featureColumns());
        selectedColumns.add(request.targetColumn());
        List<List<String>> sourceRows = datasetClient.rows(
                catalog.datasetId(), request.tableId(), table.rowCount(), selectedColumns, bearerToken);
        List<Map<String, Object>> rows = toTrainingRows(sourceRows, request, table);
        TrainingResult result = pythonClient.train(ownerId, request, rows);
        if (result == null || result.artifactId() == null || result.artifactId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "训练引擎没有返回有效模型产物");
        }

        String modelName = request.name() == null || request.name().isBlank()
                ? request.task() + " · 数据集版本 " + catalog.versionNo()
                : request.name().trim();
        ModelVersion model = new ModelVersion(
                UUID.randomUUID(), ownerId, modelName, result.task(), result.algorithm(),
                catalog.datasetId(), catalog.datasetVersionId(), catalog.versionNo(), table.tableId(),
                result.targetColumn(), json(result.featureColumns()), json(result.featureTypes()),
                result.testSize(), result.randomState(), json(result.metrics()), result.trainRows(),
                result.testRows(), result.droppedTargetRows(), result.artifactId(),
                result.confusionMatrix() == null ? null : json(result.confusionMatrix()),
                json(result.evaluationSamples()), Instant.now());
        return toResponse(repository.save(model));
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

    private void validateConfiguration(ModelTrainingRequest request, CatalogTable table) {
        if (table.rowCount() < 12 || table.rowCount() > MAX_TRAINING_ROWS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "训练数据需要 12 到 100,000 行，当前为 " + table.rowCount() + " 行");
        }
        if (request.randomState() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "随机种子不能小于 0");
        }
        if (request.featureColumns().stream().distinct().count() != request.featureColumns().size()
                || request.featureColumns().contains(request.targetColumn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "特征字段不能重复，目标字段也不能出现在特征字段中");
        }
        boolean compatible = switch (request.task()) {
            case "classification" -> List.of("random_forest", "logistic_regression").contains(request.algorithm());
            case "regression" -> List.of("random_forest", "ridge_regression").contains(request.algorithm());
            default -> false;
        };
        if (!compatible) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "任务类型与算法不兼容");
        }
        List<String> available = table.columns().stream().map(column -> column.name()).toList();
        for (String selected : concat(request.featureColumns(), request.targetColumn())) {
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

    private List<String> concat(List<String> features, String target) {
        List<String> columns = new ArrayList<>(features);
        columns.add(target);
        return columns;
    }

    private List<Map<String, Object>> toTrainingRows(
            List<List<String>> sourceRows, ModelTrainingRequest request, CatalogTable table) {
        Map<String, String> types = new LinkedHashMap<>();
        for (var column : table.columns()) {
            types.put(column.name(), column.logicalType());
        }
        List<String> selected = concat(request.featureColumns(), request.targetColumn());
        java.util.Set<String> numericColumns = new java.util.HashSet<>();
        for (int columnIndex = 0; columnIndex < selected.size(); columnIndex++) {
            String name = selected.get(columnIndex);
            String logicalType = types.get(name);
            boolean declaredNumeric = logicalType != null && List.of(
                    "INTEGER", "LONG", "INT64", "DOUBLE", "FLOAT", "FLOAT64", "DECIMAL", "NUMBER")
                    .contains(logicalType.toUpperCase(java.util.Locale.ROOT));
            boolean inferredNumeric = columnIndex < selected.size() - 1 || !request.task().equals("classification");
            if (declaredNumeric || inferredNumeric && isNumericColumn(sourceRows, columnIndex)) {
                numericColumns.add(name);
            }
        }
        return sourceRows.stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int index = 0; index < selected.size(); index++) {
                String name = selected.get(index);
                String value = index < row.size() ? row.get(index) : null;
                values.put(name, trainingValue(value, numericColumns.contains(name)));
            }
            return values;
        }).toList();
    }

    private boolean isNumericColumn(List<List<String>> rows, int columnIndex) {
        boolean hasNumber = false;
        for (List<String> row : rows) {
            String value = columnIndex < row.size() ? row.get(columnIndex) : null;
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                new BigDecimal(value.trim());
                hasNumber = true;
            } catch (NumberFormatException error) {
                return false;
            }
        }
        return hasNumber;
    }

    private Object trainingValue(String value, boolean numeric) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (numeric) {
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return value;
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
                read(model.getEvaluationSamplesJson(), new TypeReference<>() {}), model.getCreatedAt());
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
