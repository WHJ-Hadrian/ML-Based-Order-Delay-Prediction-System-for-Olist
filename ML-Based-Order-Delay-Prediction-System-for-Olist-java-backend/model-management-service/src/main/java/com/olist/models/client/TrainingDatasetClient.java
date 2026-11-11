package com.olist.models.client;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TrainingDatasetClient {

    private final RestClient client;

    public TrainingDatasetClient(
            RestClient.Builder builder,
            @Value("${olist.model-training.dataset-service-url:http://localhost:8082}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    public DatasetCatalog catalog(UUID datasetVersionId, String bearerToken) {
        try {
            return client.get()
                    .uri("/api/v1/dataset-versions/{id}/catalog", datasetVersionId)
                    .headers(headers -> headers.setBearerAuth(bearerToken))
                    .retrieve()
                    .body(DatasetCatalog.class);
        } catch (RestClientResponseException error) {
            throw upstreamError("无法读取数据集版本目录", error);
        }
    }

    private ResponseStatusException upstreamError(String message, RestClientResponseException error) {
        if (error.getStatusCode().is4xxClientError()) {
            return new ResponseStatusException(error.getStatusCode(), message);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    public record DatasetCatalog(
            UUID datasetId,
            UUID datasetVersionId,
            int versionNo,
            String status,
            String manifestHash,
            String schemaHash,
            List<CatalogTable> tables) {
    }

    public record CatalogTable(
            UUID tableId,
            String tableKey,
            String tableName,
            int ordinal,
            long rowCount,
            int columnCount,
            List<CatalogColumn> columns) {
    }

    public record CatalogColumn(UUID columnId, String name, int ordinal, String logicalType, boolean nullable) {
    }

}
