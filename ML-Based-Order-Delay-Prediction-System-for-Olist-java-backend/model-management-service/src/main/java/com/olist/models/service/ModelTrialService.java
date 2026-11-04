package com.olist.models.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrialRequest;
import com.olist.models.api.ModelTrialCompareRequest;
import com.olist.models.api.ModelTrialTestSource;
import com.olist.models.api.ModelTrialResponse;
import com.olist.models.client.PythonTrainingClient;
import com.olist.models.client.PythonTrainingClient.TrialResult;
import com.olist.models.client.TrainingDatasetClient;
import com.olist.models.client.TrainingDatasetClient.CatalogTable;
import com.olist.models.client.TrainingDatasetClient.DatasetCatalog;
import com.olist.models.entity.ModelTrial;
import com.olist.models.entity.ModelVersion;
import com.olist.models.repository.ModelTrialRepository;
import com.olist.models.repository.ModelVersionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelTrialService {

    private static final long MAX_TRIAL_ROWS = 100_000;
    private static final long MAX_TRIAL_CELLS = 2_000_000;
    private final TrainingDatasetClient datasetClient;
    private final PythonTrainingClient pythonClient;
    private final ModelVersionRepository modelRepository;
    private final ModelTrialRepository trialRepository;
    private final ObjectMapper objectMapper;

    public ModelTrialService(
            TrainingDatasetClient datasetClient,
            PythonTrainingClient pythonClient,
            ModelVersionRepository modelRepository,
            ModelTrialRepository trialRepository,
            ObjectMapper objectMapper) {
        this.datasetClient = datasetClient;
        this.pythonClient = pythonClient;
        this.modelRepository = modelRepository;
        this.trialRepository = trialRepository;
        this.objectMapper = objectMapper;
    }

    public ModelTrialResponse trial(String ownerId, String bearerToken, UUID modelId, ModelTrialRequest request) {
        ModelVersion model = modelRepository.findByIdAndOwnerId(modelId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
        DatasetCatalog catalog = datasetClient.catalog(request.datasetVersionId(), bearerToken);
        if (catalog == null
                || !request.datasetId().equals(catalog.datasetId())
                || !request.datasetVersionId().equals(catalog.datasetVersionId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选测试数据集版本");
        }
        if (!"READY".equalsIgnoreCase(catalog.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能使用 READY 数据集版本进行试炼");
        }
        requireIndependentDataset(model, catalog);
        CatalogTable table = catalog.tables().stream()
                .filter(candidate -> request.tableId().equals(candidate.tableId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选测试数据表"));
        List<String> features = read(model.getFeatureColumnsJson(), new TypeReference<>() {});
        Map<String, String> featureTypes = read(model.getFeatureTypesJson(), new TypeReference<>() {});
        String target = request.targetColumn() == null || request.targetColumn().isBlank()
                ? null : request.targetColumn().trim();
        if (target != null && !target.equals(model.getTargetColumn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "评估目标字段必须与模型登记的目标字段一致");
        }
        List<String> selected = new ArrayList<>(features);
        if (target != null) {
            selected.add(target);
        }
        Set<String> available = new HashSet<>(table.columns().stream().map(column -> column.name()).toList());
        if (!available.containsAll(selected)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "测试数据表缺少模型所需字段");
        }
        if (table.rowCount() < 1 || table.rowCount() > MAX_TRIAL_ROWS
                || table.rowCount() * selected.size() > MAX_TRIAL_CELLS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "试炼数据超过单次行数或字段资源上限");
        }
        List<Map<String, Object>> rows = toRows(
                datasetClient.rows(request.datasetVersionId(), request.tableId(), table.rowCount(), selected, bearerToken),
                selected, featureTypes);
        TrialResult result = pythonClient.trial(ownerId, model.getArtifactId(), rows, target);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "试炼引擎没有返回结果");
        }
        UUID comparisonId = UUID.randomUUID();
        ModelTrial saved = trialRepository.save(new ModelTrial(
                UUID.randomUUID(), comparisonId, ownerId, model.getId(), catalog.datasetId(), catalog.datasetVersionId(),
                catalog.versionNo(), table.tableId(), target, result.predictedRows(), result.evaluatedRows(),
                result.ignoredTargetRows(), json(result.metrics()),
                result.confusionMatrix() == null ? null : json(result.confusionMatrix()),
                json(result.evaluationSamples()), Instant.now()));
        return toResponse(saved, model.getTask());
    }

    @Transactional
    public List<ModelTrialResponse> compare(String ownerId, String bearerToken, ModelTrialCompareRequest request) {
        if (request.modelIds().size() < 2 || request.modelIds().size() > 5
                || new HashSet<>(request.modelIds()).size() != request.modelIds().size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "模型比较需要 2 到 5 个不重复的模型版本");
        }
        List<ModelVersion> models = request.modelIds().stream()
                .map(id -> modelRepository.findByIdAndOwnerId(id, ownerId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在")))
                .toList();
        ModelVersion first = models.get(0);
        String requestedTarget = request.targetColumn() == null || request.targetColumn().isBlank()
                ? null : request.targetColumn().trim();
        for (ModelVersion model : models) {
            if (!first.getTask().equals(model.getTask())
                    || !first.getTargetColumn().equals(model.getTargetColumn())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "只能比较任务类型和目标字段相同的模型版本");
            }
        }
        if (requestedTarget != null && !requestedTarget.equals(first.getTargetColumn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "评估目标字段必须与模型登记的目标字段一致");
        }

        List<Map<String, String>> featureTypes = models.stream()
                .map(model -> read(model.getFeatureTypesJson(),
                        new TypeReference<Map<String, String>>() {})).toList();
        List<List<String>> features = models.stream()
                .map(model -> read(model.getFeatureColumnsJson(),
                        new TypeReference<List<String>>() {})).toList();
        if (new HashSet<>(request.testSources()).size() != request.testSources().size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "同一数据集版本和数据表不能重复加入比较");
        }
        List<PreparedTestSource> preparedSources = new ArrayList<>();
        long totalCells = 0;
        for (ModelTrialTestSource source : request.testSources()) {
            DatasetCatalog catalog = datasetClient.catalog(source.datasetVersionId(), bearerToken);
            if (catalog == null || !source.datasetId().equals(catalog.datasetId())
                    || !source.datasetVersionId().equals(catalog.datasetVersionId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到所选测试数据集版本");
            }
            if (!"READY".equalsIgnoreCase(catalog.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "只能使用 READY 数据集版本进行试炼");
            }
            for (ModelVersion model : models) {
                requireIndependentDataset(model, catalog);
            }
            CatalogTable table = catalog.tables().stream()
                    .filter(candidate -> source.tableId().equals(candidate.tableId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "找不到所选测试数据表"));
            Set<String> available = new HashSet<>(table.columns().stream()
                    .map(column -> column.name()).toList());
            LinkedHashSet<String> selectedSet = new LinkedHashSet<>();
            for (List<String> modelFeatures : features) {
                if (modelFeatures.isEmpty() || !available.containsAll(modelFeatures)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "测试数据表缺少所选模型的特征字段");
                }
                selectedSet.addAll(modelFeatures);
            }
            if (requestedTarget != null) {
                if (!available.contains(requestedTarget)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "测试数据表缺少评估目标字段");
                }
                selectedSet.add(requestedTarget);
            }
            List<String> selected = List.copyOf(selectedSet);
            if (table.rowCount() < 1 || table.rowCount() > MAX_TRIAL_ROWS) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "每份比较数据需要 1 到 100,000 行");
            }
            totalCells += table.rowCount() * selected.size();
            if (totalCells > MAX_TRIAL_CELLS) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "所有比较数据合计超过 2,000,000 个行字段单元的资源上限");
            }
            preparedSources.add(new PreparedTestSource(catalog, table, selected));
        }

        List<List<List<String>>> rowsBySource = preparedSources.stream()
                .map(source -> datasetClient.rows(source.catalog().datasetVersionId(), source.table().tableId(),
                        source.table().rowCount(), source.columns(), bearerToken))
                .toList();
        List<ModelTrialResponse> results = new ArrayList<>();
        UUID comparisonId = UUID.randomUUID();
        for (int sourceIndex = 0; sourceIndex < preparedSources.size(); sourceIndex++) {
            PreparedTestSource source = preparedSources.get(sourceIndex);
            for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
                ModelVersion model = models.get(modelIndex);
                List<String> modelColumns = new ArrayList<>(features.get(modelIndex));
                if (requestedTarget != null) {
                    modelColumns.add(requestedTarget);
                }
                List<Map<String, Object>> rows = toRows(rowsBySource.get(sourceIndex),
                        source.columns(), modelColumns, featureTypes.get(modelIndex));
                TrialResult result = pythonClient.trial(
                        ownerId, model.getArtifactId(), rows, requestedTarget);
                if (result == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "试炼引擎没有返回结果");
                }
                ModelTrial saved = trialRepository.save(new ModelTrial(
                        UUID.randomUUID(), comparisonId, ownerId, model.getId(), source.catalog().datasetId(),
                        source.catalog().datasetVersionId(), source.catalog().versionNo(), source.table().tableId(),
                        requestedTarget, result.predictedRows(), result.evaluatedRows(), result.ignoredTargetRows(),
                        json(result.metrics()), result.confusionMatrix() == null ? null : json(result.confusionMatrix()),
                        json(result.evaluationSamples()), Instant.now()));
                results.add(toResponse(saved, model.getTask()));
            }
        }
        return List.copyOf(results);
    }

    private record PreparedTestSource(DatasetCatalog catalog, CatalogTable table, List<String> columns) {
    }

    @Transactional(readOnly = true)
    public List<ModelTrialResponse> list(String ownerId, UUID modelId) {
        ModelVersion model = modelRepository.findByIdAndOwnerId(modelId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
        String task = model.getTask();
        return trialRepository.findTop100ByOwnerIdAndModelIdOrderByCreatedAtDesc(ownerId, modelId).stream()
                .map(trial -> toResponse(trial, task)).toList();
    }

    @Transactional(readOnly = true)
    public ModelTrialResponse get(String ownerId, UUID id) {
        ModelTrial trial = trialRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型试炼记录不存在"));
        ModelVersion model = modelRepository.findByIdAndOwnerId(trial.getModelId(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
        return toResponse(trial, model.getTask());
    }

    @Transactional(readOnly = true)
    public List<ModelTrialResponse> getComparison(String ownerId, UUID comparisonId) {
        List<ModelTrial> comparison = trialRepository
                .findAllByComparisonIdAndOwnerIdOrderByCreatedAtAsc(comparisonId, ownerId);
        if (comparison.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型比较记录不存在");
        }
        return comparison.stream().map(trial -> {
            ModelVersion model = modelRepository.findByIdAndOwnerId(trial.getModelId(), ownerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
            return toResponse(trial, model.getTask());
        }).toList();
    }

    private List<Map<String, Object>> toRows(
            List<List<String>> sourceRows, List<String> selected, Map<String, String> featureTypes) {
        return toRows(sourceRows, selected, selected, featureTypes);
    }

    private void requireIndependentDataset(ModelVersion model, DatasetCatalog catalog) {
        if (model.getDatasetId().equals(catalog.datasetId())
                || model.getDatasetVersionId().equals(catalog.datasetVersionId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "试炼数据必须来自与训练数据不同的数据集，避免训练数据泄漏");
        }
    }

    private List<Map<String, Object>> toRows(
            List<List<String>> sourceRows,
            List<String> sourceColumns,
            List<String> modelColumns,
            Map<String, String> featureTypes) {
        return sourceRows.stream().map(row -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String name : modelColumns) {
                int index = sourceColumns.indexOf(name);
                String value = index >= 0 && index < row.size() ? row.get(index) : null;
                String type = featureTypes.get(name);
                values.put(name, "numeric".equalsIgnoreCase(type) ? numericValue(value) : value);
            }
            return values;
        }).toList();
    }

    private Object numericValue(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new BigDecimal(value.trim()); } catch (NumberFormatException ignored) { return null; }
    }

    private ModelTrialResponse toResponse(ModelTrial trial, String task) {
        return new ModelTrialResponse(
                trial.getId(), trial.getComparisonId(), trial.getModelId(), trial.getDatasetId(), trial.getDatasetVersionId(),
                trial.getDatasetVersionNo(), trial.getTableId(), trial.getTargetColumn(), task,
                trial.getPredictedRows(), trial.getEvaluatedRows(), trial.getIgnoredTargetRows(),
                read(trial.getMetricsJson(), new TypeReference<>() {}),
                trial.getConfusionMatrixJson() == null ? null
                        : read(trial.getConfusionMatrixJson(), new TypeReference<>() {}),
                read(trial.getEvaluationSamplesJson(), new TypeReference<>() {}), trial.getCreatedAt());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法序列化模型试炼结果", error); }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("模型试炼记录已损坏", error); }
    }
}
