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

    private static final int PAGE_SIZE = 500;
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

    public List<List<String>> rows(UUID datasetVersionId, UUID tableId, long expectedRows,
            List<String> selectedColumns, String bearerToken) {
        if (expectedRows < 0 || expectedRows > 100_000) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "单次训练最多读取 100,000 行数据");
        }
        List<List<String>> result = new java.util.ArrayList<>((int) expectedRows);
        for (int offset = 0; offset < expectedRows; offset += PAGE_SIZE) {
            int pageOffset = offset;
            DatasetRows page;
            try {
                page = client.get()
                        .uri(uri -> uri.path("/api/v1/datasets/{datasetId}/tables/{tableId}/rows")
                                .queryParam("offset", pageOffset)
                                .queryParam("limit", (int) Math.min(PAGE_SIZE, expectedRows - pageOffset))
                                .build(datasetVersionId, tableId))
                        .headers(headers -> headers.setBearerAuth(bearerToken))
                        .retrieve()
                        .body(DatasetRows.class);
            } catch (RestClientResponseException error) {
                throw upstreamError("无法读取所选数据表", error);
            }
            if (page == null || page.rows() == null || page.rows().isEmpty()) {
                break;
            }
            for (List<String> row : page.rows()) {
                result.add(selectedColumns.stream()
                        .map(name -> {
                            int index = page.columns().indexOf(name);
                            return index >= 0 && index < row.size() ? row.get(index) : null;
                        })
                        .toList());
            }
        }
        if (result.size() != expectedRows) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "数据集版本记录数在训练期间发生变化，请重新加载后再试");
        }
        return result;
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

    private record DatasetRows(
            UUID tableId,
            String name,
            int offset,
            int limit,
            long totalRows,
            List<String> columns,
            List<List<String>> rows) {
    }
}
