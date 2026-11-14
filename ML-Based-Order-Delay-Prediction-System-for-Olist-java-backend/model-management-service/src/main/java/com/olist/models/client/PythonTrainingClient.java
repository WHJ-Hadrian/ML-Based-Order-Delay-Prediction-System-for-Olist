package com.olist.models.client;

import com.olist.models.api.ModelTrainingRequest;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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
        // Python/uvicorn 不支持 h2c 升级：JDK HttpClient 默认会先发 HTTP/2 升级请求，
        // 会被 uvicorn 以 400 "Invalid HTTP request received" 拒绝，因此强制 HTTP/1.1。
        this.client = builder
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build();
        this.internalToken = internalToken;
    }

    public TrainingResult train(String ownerId, ModelTrainingRequest request, DataSource dataSource) {
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
                            dataSource,
                            request.task(),
                            request.algorithm(),
                            request.targetColumn(),
                            request.featureColumns(),
                            request.testSize(),
                            request.validationSize(),
                            request.randomState(),
                            request.splitStrategy(),
                            request.timeColumn(),
                            request.groupColumn(),
                            request.cvFolds(),
                            request.explain()))
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

    public TrialResult trial(String ownerId, String artifactId, DataSource dataSource,
            String targetColumn) {
        if (internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型试炼服务令牌尚未配置");
        }
        try {
            return client.post()
                    .uri("/internal/v1/model-trials")
                    .headers(headers -> headers.setBearerAuth(internalToken))
                    .body(new TrialRequest(ownerId, artifactId, dataSource, targetColumn))
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

    public Map<String, Object> manifest(String ownerId, String artifactId) {
        try {
            return client.get()
                    .uri(uri -> uri.path("/internal/v1/model-artifacts/{artifactId}/manifest")
                            .queryParam("ownerId", ownerId)
                            .build(artifactId))
                    .headers(headers -> headers.setBearerAuth(requiredToken()))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException error) {
            throw artifactError(error);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型产物服务暂不可用", error);
        }
    }

    public void streamArtifact(String ownerId, String artifactId, OutputStream output) {
        try {
            client.get()
                    .uri(uri -> uri.path("/internal/v1/model-artifacts/{artifactId}/export.zip")
                            .queryParam("ownerId", ownerId)
                            .build(artifactId))
                    .headers(headers -> headers.setBearerAuth(requiredToken()))
                    .exchange((request, response) -> {
                        InputStream input = response.getBody();
                        if (response.getStatusCode().is2xxSuccessful()) {
                            if (input == null) {
                                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型产物导出为空");
                            }
                            input.transferTo(output);
                            return null;
                        }
                        throw artifactError(response.getStatusCode());
                    });
        } catch (RestClientResponseException error) {
            throw artifactError(error);
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型产物导出失败", error);
        }
    }

    public Map<String, Object> explainModel(Map<String, Object> payload) {
        try {
            return client.post()
                    .uri("/internal/v1/model-explanations")
                    .headers(headers -> headers.setBearerAuth(requiredToken()))
                    .body(payload)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException error) {
            throw insightError(error.getStatusCode(), "模型解释");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型解释服务暂不可用", error);
        }
    }

    public void streamReport(Map<String, Object> payload, OutputStream output) {
        try {
            client.post()
                    .uri("/internal/v1/model-reports")
                    .headers(headers -> headers.setBearerAuth(requiredToken()))
                    .body(payload)
                    .exchange((request, response) -> {
                        InputStream input = response.getBody();
                        if (response.getStatusCode().is2xxSuccessful() && input != null) {
                            input.transferTo(output);
                            return null;
                        }
                        throw insightError(response.getStatusCode(), "模型报告");
                    });
        } catch (RestClientResponseException error) {
            throw insightError(error.getStatusCode(), "模型报告");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型报告服务暂不可用", error);
        }
    }

    private ResponseStatusException insightError(org.springframework.http.HttpStatusCode status, String label) {
        if (status.value() == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, label + "所需的模型产物不存在");
        }
        if (status.is4xxClientError()) {
            return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "请求无效");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, label + "服务暂不可用");
    }

    private String requiredToken() {
        if (internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型训练服务令牌尚未配置");
        }
        return internalToken;
    }

    private ResponseStatusException artifactError(RestClientResponseException error) {
        if (error.getStatusCode().value() == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "模型产物不存在");
        }
        if (error.getStatusCode().is4xxClientError()) {
            return new ResponseStatusException(error.getStatusCode(), "模型产物请求无效");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型产物服务暂不可用");
    }

    private ResponseStatusException artifactError(org.springframework.http.HttpStatusCode status) {
        int statusCode = status.value();
        if (statusCode == 404) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "模型产物不存在");
        }
        if (status.is4xxClientError()) {
            return new ResponseStatusException(status, "模型产物请求无效");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型产物服务暂不可用");
    }

    private record TrainingRequest(
            String ownerId,
            UUID datasetId,
            UUID datasetVersionId,
            UUID tableId,
            DataSource dataSource,
            String task,
            String algorithm,
            String targetColumn,
            List<String> featureColumns,
            double testSize,
            Double validationSize,
            int randomState,
            String splitStrategy,
            String timeColumn,
            String groupColumn,
            Integer cvFolds,
            Boolean explain) {
    }

    private record TrialRequest(
            String ownerId,
            String artifactId,
            DataSource dataSource,
            String targetColumn) {
    }

    public record DataSource(
            UUID datasetVersionId,
            UUID tableId,
            String ownerId,
            List<String> columns,
            int maxRows) {
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
            List<Map<String, Object>> evaluationSamples,
            Integer schemaVersion,
            String datasetFingerprint,
            Map<String, Object> splitSummary,
            Map<String, Object> metricDetails,
            Map<String, Double> validationMetrics,
            Map<String, Object> validationMetricDetails,
            Integer validationRows,
            Map<String, Object> explainability,
            Double trainingSeconds,
            Long artifactSizeBytes,
            String artifactSha256) {
    }

    public record TrialResult(
            String task,
            String targetColumn,
            int predictedRows,
            int evaluatedRows,
            int ignoredTargetRows,
            Map<String, Double> metrics,
            Map<String, Object> metricDetails,
            Map<String, Object> confusionMatrix,
            List<Map<String, Object>> evaluationSamples) {
    }
}
