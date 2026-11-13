package com.olist.analytics.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 读取 dataset-service 的执行快照：物理表名、物理列名、逻辑类型、schema 哈希。 */
@Component
public class SnapshotClient {

    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SnapshotClient(@Value("${olist.dataset-service.url:http://localhost:8082}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Snapshot fetch(UUID datasetVersionId, String authorization) {
        RestClient.RequestHeadersSpec<?> spec = client.get()
                .uri("/internal/dataset-versions/{id}/execution-snapshot", datasetVersionId);
        if (authorization != null && !authorization.isBlank()) {
            spec = spec.header("Authorization", authorization);
        }
        String body = spec.retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("数据集快照为空");
        }
        try {
            return objectMapper.readValue(body, Snapshot.class);
        } catch (Exception error) {
            throw new IllegalStateException("数据集快照解析失败: " + error.getMessage(), error);
        }
    }

    public record Snapshot(
            UUID datasetVersionId,
            String status,
            String schemaHash,
            String clickhouseDatabase,
            long rowCount,
            List<Table> tables) {
    }

    public record Table(
            UUID tableId,
            String tableKey,
            String tableName,
            String physicalTable,
            long rowCount,
            List<Column> columns) {
    }

    public record Column(
            UUID columnId,
            UUID fieldId,
            String sourceName,
            String physicalName,
            String logicalType,
            String clickhouseType,
            boolean nullable) {
    }
}
