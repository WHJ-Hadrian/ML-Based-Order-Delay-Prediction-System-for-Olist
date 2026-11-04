package com.olist.models.client;

import com.olist.models.api.ModelTrainingRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PythonTrainingClient {

    private final RestClient client;
    private final String internalToken;

    public PythonTrainingClient(
            RestClient.Builder builder,
            @Value("${olist.model-training.model-serving-url:http://localhost:8090}") String baseUrl,
            @Value("${olist.model-training.internal-token:}") String internalToken) {
        this.client = builder.baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    public TrainingResult train(String ownerId, ModelTrainingRequest request, List<Map<String, Object>> rows) {
        if (internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型训练服务令牌尚未配置");
        }
        try {
            return client.post()
                    .uri("/internal/v1/model-training-runs")
                    .headers(headers -> headers.setBearerAuth(internalToken))
                    .body(new TrainingRequest(
                            ownerId,
                            request.datasetId(),
                            request.datasetVersionId(),
                            request.tableId(),
                            rows,
                            request.task(),
                            request.algorithm(),
                            request.targetColumn(),
                            request.featureColumns(),
                            request.testSize(),
                            request.randomState()))
                    .retrieve()
                    .body(TrainingResult.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "模型训练配置或数据无效: " + error.getResponseBodyAsString());
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型训练引擎暂不可用");
        }
    }

    public TrialResult trial(String ownerId, String artifactId, List<Map<String, Object>> rows,
            String targetColumn) {
        if (internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型试炼服务令牌尚未配置");
        }
        try {
            return client.post()
                    .uri("/internal/v1/model-trials")
                    .headers(headers -> headers.setBearerAuth(internalToken))
                    .body(new TrialRequest(ownerId, artifactId, rows, targetColumn))
                    .retrieve()
                    .body(TrialResult.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "模型试炼配置或数据无效: " + error.getResponseBodyAsString());
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型试炼引擎暂不可用");
        }
    }

    private record TrainingRequest(
            String ownerId,
            UUID datasetId,
            UUID datasetVersionId,
            UUID tableId,
            List<Map<String, Object>> rows,
            String task,
            String algorithm,
            String targetColumn,
            List<String> featureColumns,
            double testSize,
            int randomState) {
    }

    private record TrialRequest(
            String ownerId,
            String artifactId,
            List<Map<String, Object>> rows,
            String targetColumn) {
    }

    public record TrainingResult(
            String artifactId,
            UUID datasetId,
            UUID datasetVersionId,
            UUID tableId,
            String task,
            String algorithm,
            String targetColumn,
            List<String> featureColumns,
            Map<String, String> featureTypes,
            double testSize,
            int randomState,
            Map<String, Double> metrics,
            int trainRows,
            int testRows,
            int droppedTargetRows,
            Map<String, Object> confusionMatrix,
            List<Map<String, Object>> evaluationSamples) {
    }

    public record TrialResult(
            String task,
            String targetColumn,
            int predictedRows,
            int evaluatedRows,
            int ignoredTargetRows,
            Map<String, Double> metrics,
            Map<String, Object> confusionMatrix,
            List<Map<String, Object>> evaluationSamples) {
    }
}
