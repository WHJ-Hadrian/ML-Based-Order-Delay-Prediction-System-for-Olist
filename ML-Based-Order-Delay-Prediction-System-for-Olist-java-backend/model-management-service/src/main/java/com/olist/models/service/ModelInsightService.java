package com.olist.models.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelExplanationRequest;
import com.olist.models.api.ModelReportRecordResponse;
import com.olist.models.api.ModelReportRequest;
import com.olist.models.client.PythonTrainingClient;
import com.olist.models.entity.ModelReportExport;
import com.olist.models.entity.ModelVersion;
import com.olist.models.repository.ModelReportExportRepository;
import com.olist.models.repository.ModelVersionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelInsightService {

    private final ModelVersionRepository modelRepository;
    private final ModelReportExportRepository reportRepository;
    private final PythonTrainingClient pythonClient;
    private final ObjectMapper objectMapper;

    public ModelInsightService(
            ModelVersionRepository modelRepository,
            ModelReportExportRepository reportRepository,
            PythonTrainingClient pythonClient,
            ObjectMapper objectMapper) {
        this.modelRepository = modelRepository;
        this.reportRepository = reportRepository;
        this.pythonClient = pythonClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> explain(String ownerId, UUID modelId, ModelExplanationRequest request) {
        ModelVersion model = requireModel(ownerId, modelId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("artifactId", model.getArtifactId());
        payload.put("rows", request.rows());
        if (request.threshold() != null) {
            payload.put("threshold", request.threshold());
        }
        payload.put("includeCounterfactuals", request.includeCounterfactuals());
        return pythonClient.explainModel(payload);
    }

    @Transactional
    public ReportDownload report(String ownerId, UUID modelId, ModelReportRequest request, OutputStream output) {
        ModelVersion model = requireModel(ownerId, modelId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("artifactId", model.getArtifactId());
        payload.put("format", request.format());
        payload.put("sections", request.sections());
        payload.put("title", request.title());
        payload.put("modelName", model.getName());
        payload.put("modelVersion", "v" + model.getVersionNo());
        payload.put("comparison", request.comparison());
        if (request.sampleExplanation() != null) {
            payload.put("sampleExplanation", request.sampleExplanation());
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        pythonClient.streamReport(payload, buffer);
        try {
            buffer.writeTo(output);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型报告写出失败", error);
        }
        String fileName = safeFileName(request.title(), extension(request.format()));
        reportRepository.save(new ModelReportExport(
                UUID.randomUUID(), ownerId, modelId, model.getArtifactId(), request.format(),
                request.title(), fileName, json(request.sections()), Instant.now()));
        return new ReportDownload(request.format(), fileName);
    }

    @Transactional(readOnly = true)
    public List<ModelReportRecordResponse> records(String ownerId, UUID modelId) {
        requireModel(ownerId, modelId);
        return reportRepository.findTop50ByOwnerIdAndModelIdOrderByCreatedAtDesc(ownerId, modelId).stream()
                .map(record -> new ModelReportRecordResponse(
                        record.getId(), record.getModelId(), record.getFormat(), record.getTitle(),
                        record.getFileName(), record.getCreatedAt()))
                .toList();
    }

    private ModelVersion requireModel(String ownerId, UUID modelId) {
        ModelVersion model = modelRepository.findByIdAndOwnerId(modelId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型版本不存在"));
        if (model.getArtifactId() == null || model.getArtifactId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "模型版本还没有可用的训练产物");
        }
        return model;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化报告导出记录", error);
        }
    }

    private String extension(String format) {
        return "docx".equals(format) ? "docx" : format;
    }

    private String safeFileName(String title, String extension) {
        String safe = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        if (safe.isEmpty()) {
            safe = "model-report";
        }
        return safe + "." + extension;
    }

    public record ReportDownload(String format, String fileName) {
    }
}
