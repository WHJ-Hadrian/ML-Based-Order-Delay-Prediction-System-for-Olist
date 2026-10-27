package com.olist.datasets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
class RuleWorkbookIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBefore() {
        cleanDatabase();
    }

    @AfterEach
    void cleanAfter() {
        cleanDatabase();
    }

    @Test
    void importsFieldsResolvesBindingsAndCompilesJoinWorkbook() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "orders.csv", """
                order_id,customer_id,order_status
                o1,c1,delivered
                o2,,shipped
                """);
        addCsv(datasetId, "customers.csv", """
                customer_id,customer_city
                c1,sp
                """);
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.length()").value(2))
                .andReturn());
        UUID ordersTableId = findTable(dataset, "orders");
        UUID customersTableId = findTable(dataset, "customers");

        mockMvc.perform(post("/api/v1/fields/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("datasetVersionId", datasetId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(5))
                .andExpect(jsonPath("$.skipped").value(0));

        mockMvc.perform(post("/api/v1/fields/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("datasetVersionId", datasetId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(5));

        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/fields/catalog")
                        .param("datasetVersionId", datasetId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andReturn());
        UUID orderIdFieldId = findFieldId(catalog, ordersTableId, "order_id");
        UUID orderCustomerFieldId = findFieldId(catalog, ordersTableId, "customer_id");
        UUID customerIdFieldId = findFieldId(catalog, customersTableId, "customer_id");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "订单客户关联清洗");
        request.put("datasetVersionId", datasetId.toString());
        request.set("document", objectMapper.readTree(workbookDocument(
                datasetId, ordersTableId, customersTableId,
                orderIdFieldId, orderCustomerFieldId, customerIdFieldId)));

        MvcResult created = mockMvc.perform(post("/api/v1/rule-workbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.datasetVersionId").value(datasetId.toString()))
                .andReturn();
        JsonNode workbook = readJson(created);
        String workbookId = workbook.path("id").asText();
        long revision = workbook.path("revision").asLong();

        ObjectNode draftRequest = objectMapper.createObjectNode();
        draftRequest.set("document", objectMapper.readTree(workbookDocument(
                datasetId, ordersTableId, customersTableId,
                orderIdFieldId, orderCustomerFieldId, customerIdFieldId)));
        draftRequest.put("expectedRevision", revision);
        mockMvc.perform(put("/api/v1/rule-workbooks/{id}/draft", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftRequest.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision + 1));

        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.nodeCount").value(3))
                .andExpect(jsonPath("$.compiledPlan.nodes.length()").value(3))
                .andExpect(jsonPath("$.compiledPlan.edges.length()").value(1))
                .andExpect(jsonPath("$.compiledPlan.edges[0].from").value("relations:join_customer"))
                .andExpect(jsonPath("$.compiledPlan.edges[0].to").value("gates:gate_join"));

        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/publish", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        MvcResult runCreated = mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PREVIEW","sampleSize":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.mode").value("PREVIEW"))
                .andExpect(jsonPath("$.sampleSize").value(100))
                .andExpect(jsonPath("$.seed").value(42))
                .andReturn();
        UUID runId = UUID.fromString(readJson(runCreated).path("id").asText());

        mockMvc.perform(get("/api/v1/rule-runs/{runId}/execution", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.workbookVersionContentHash").isNotEmpty())
                .andExpect(jsonPath("$.compiledPlan.nodeCount").value(3))
                .andExpect(jsonPath("$.compiledPlan.nodes.length()").value(3))
                .andExpect(jsonPath("$.compiledPlan.datasetVersionId").value(datasetId.toString()));

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/finish", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUCCEEDED"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/start", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/start", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/finish", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUCCEEDED"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/rule-runs/{runId}/nodes/{nodeId}", runId, "missing:node")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeKind":"CHECK","status":"RUNNING"}
                                """))
                .andExpect(status().isBadRequest());

        reportNode(runId, "field_checks:check_order_id", "CHECK", "RUNNING");
        reportNode(runId, "field_checks:check_order_id", "CHECK", "SUCCEEDED");
        reportNode(runId, "relations:join_customer", "JOIN", "RUNNING");
        reportNode(runId, "relations:join_customer", "JOIN", "SUCCEEDED");
        reportNode(runId, "gates:gate_join", "GATE", "SUCCEEDED");

        mockMvc.perform(put("/api/v1/rule-runs/{runId}/nodes/{nodeId}", runId, "relations:join_customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeKind":"JOIN","status":"FAILED","errorMessage":"late failure"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/rule-runs/{runId}/nodes", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.nodeId == 'relations:join_customer')].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[?(@.nodeId == 'relations:join_customer')].finishedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/finish", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUCCEEDED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/finish", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SUCCEEDED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void rejectsPublishWhenDatasetVersionIsNotReady() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "orders.csv", """
                order_id,order_status
                o1,delivered
                """);

        ObjectNode document = objectMapper.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("datasetVersionId", datasetId.toString());
        document.putArray("sheets");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "未就绪数据集规则库");
        request.put("datasetVersionId", datasetId.toString());
        request.set("document", document);

        MvcResult created = mockMvc.perform(post("/api/v1/rule-workbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        String workbookId = readJson(created).path("id").asText();

        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/publish", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("READY")));
    }

    private void reportNode(UUID runId, String nodeId, String nodeKind, String status) throws Exception {
        mockMvc.perform(put("/api/v1/rule-runs/{runId}/nodes/{nodeId}", runId, nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeKind":"%s","status":"%s","metrics":{"rows":2},"sampleUri":"s3://samples/%s.jsonl"}
                                """.formatted(nodeKind, status, nodeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value(nodeId))
                .andExpect(jsonPath("$.nodeKind").value(nodeKind))
                .andExpect(jsonPath("$.status").value(status));
    }

    private UUID createDataset() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"规则库集成测试"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(readJson(result).path("id").asText());
    }

    private void addCsv(UUID datasetId, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", datasetId).file(file))
                .andExpect(status().isOk());
    }

    private void markReady(UUID datasetId) throws Exception {
        mockMvc.perform(post("/api/v1/datasets/{id}/ready", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private UUID findTable(JsonNode dataset, String name) {
        for (JsonNode table : dataset.path("tables")) {
            if (name.equals(table.path("name").asText())) {
                return UUID.fromString(table.path("id").asText());
            }
        }
        throw new AssertionError("Table not found: " + name);
    }

    private UUID findFieldId(JsonNode catalog, UUID tableId, String columnName) {
        for (JsonNode item : catalog) {
            if (tableId.toString().equals(item.path("tableId").asText())
                    && columnName.equals(item.path("columnName").asText())) {
                return UUID.fromString(item.path("fieldId").asText());
            }
        }
        throw new AssertionError("Field not found: " + columnName);
    }

    private String workbookDocument(
            UUID datasetId,
            UUID ordersTableId,
            UUID customersTableId,
            UUID orderIdFieldId,
            UUID orderCustomerFieldId,
            UUID customerIdFieldId) {
        return """
                {
                  "schemaVersion": 1,
                  "datasetVersionId": "%s",
                  "sheets": [
                    {
                      "key": "field_checks",
                      "title": "字段检查",
                      "kind": "FIELD_RULES",
                      "columns": [],
                      "rows": [
                        {
                          "id": "check_order_id",
                          "enabled": true,
                          "cells": {
                            "group": "基础检查",
                            "table": { "type": "TABLE", "tableId": "%s" },
                            "field": { "type": "FIELD", "fieldId": "%s", "nameSnapshot": "order_id" },
                            "condition": {
                              "op": "IS_NULL",
                              "args": [
                                { "type": "FIELD", "fieldId": "%s", "nameSnapshot": "order_id" }
                              ]
                            },
                            "effect": "QUARANTINE_ROWS",
                            "outputAlias": "orders_checked",
                            "blocking": "NONE"
                          }
                        }
                      ]
                    },
                    {
                      "key": "relations",
                      "title": "多表关联",
                      "kind": "RELATIONS",
                      "columns": [],
                      "rows": [
                        {
                          "id": "join_customer",
                          "enabled": true,
                          "cells": {
                            "group": "订单宽表",
                            "leftInput": { "type": "TABLE", "tableId": "%s", "alias": "orders" },
                            "leftField": { "type": "FIELD", "fieldId": "%s", "nameSnapshot": "customer_id" },
                            "rightInput": { "type": "TABLE", "tableId": "%s", "alias": "customers" },
                            "rightField": { "type": "FIELD", "fieldId": "%s", "nameSnapshot": "customer_id" },
                            "joinType": "LEFT",
                            "cardinality": "N:1",
                            "outputAlias": "orders_with_customers"
                          }
                        }
                      ]
                    },
                    {
                      "key": "gates",
                      "title": "质量门禁",
                      "kind": "GATES",
                      "columns": [],
                      "rows": [
                        {
                          "id": "gate_join",
                          "enabled": true,
                          "cells": {
                            "group": "关联质量",
                            "metricRef": "orders_with_customers.match_rate",
                            "comparator": "GTE",
                            "threshold": 0.95,
                            "effect": "BLOCK_PUBLISH"
                          }
                        }
                      ]
                    }
                  ]
                }
                """.formatted(
                datasetId,
                ordersTableId,
                orderIdFieldId,
                orderIdFieldId,
                ordersTableId,
                orderCustomerFieldId,
                customersTableId,
                customerIdFieldId);
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM rule_run_nodes");
        jdbcTemplate.update("DELETE FROM rule_runs");
        jdbcTemplate.update("DELETE FROM rule_workbook_versions");
        jdbcTemplate.update("DELETE FROM rule_workbook_drafts");
        jdbcTemplate.update("DELETE FROM rule_workbooks");
        jdbcTemplate.update("DELETE FROM dataset_field_bindings");
        jdbcTemplate.update("DELETE FROM dataset_columns");
        jdbcTemplate.update("DELETE FROM field_definitions");
        jdbcTemplate.update("DELETE FROM cleaning_rules");
        jdbcTemplate.update("DELETE FROM dataset_files");
        jdbcTemplate.update("DELETE FROM dataset_tables");
        jdbcTemplate.update("DELETE FROM dataset_versions");
    }
}
