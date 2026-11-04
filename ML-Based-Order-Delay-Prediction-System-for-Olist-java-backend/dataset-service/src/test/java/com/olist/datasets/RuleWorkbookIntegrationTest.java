package com.olist.datasets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest(properties = "olist.rule-worker.internal-token=test-worker-token")
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
    void datasetVersionsExposeTransformationLineage() throws Exception {
        UUID datasetId = createDataset();
        UUID sourceRunId = UUID.randomUUID();
        UUID sourceWorkbookVersionId = UUID.randomUUID();
        jdbcTemplate.update(
                "UPDATE dataset_versions SET source_type = ?, source_run_id = ?, "
                        + "source_workbook_version_id = ?, source_node_id = ? WHERE id = ?",
                "RULE_OUTPUT",
                sourceRunId.toString(),
                sourceWorkbookVersionId.toString(),
                "output_training_data",
                datasetId.toString());

        mockMvc.perform(get("/api/v1/datasets/{id}/versions", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceType").value("RULE_OUTPUT"))
                .andExpect(jsonPath("$[0].sourceRunId").value(sourceRunId.toString()))
                .andExpect(jsonPath("$[0].sourceWorkbookVersionId")
                        .value(sourceWorkbookVersionId.toString()))
                .andExpect(jsonPath("$[0].sourceNodeId").value("output_training_data"));
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

        ObjectNode workbookDocument = (ObjectNode) objectMapper.readTree(workbookDocument(
                datasetId, ordersTableId, customersTableId,
                orderIdFieldId, orderCustomerFieldId, customerIdFieldId));
        ((ObjectNode) workbookDocument.path("sheets").get(2).path("rows").get(0))
                .put("enabled", false);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "订单客户关联清洗");
        request.put("datasetVersionId", datasetId.toString());
        request.set("document", workbookDocument);

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
        draftRequest.set("document", workbookDocument);
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
                .andExpect(jsonPath("$.nodeCount").value(2))
                .andExpect(jsonPath("$.compiledPlan.nodes.length()").value(2))
                .andExpect(jsonPath("$.compiledPlan.edges.length()").value(0));

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

        MvcResult defaultRun = mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("PREVIEW"))
                .andReturn();
        mockMvc.perform(post("/api/v1/rule-runs/{runId}/cancel",
                        readJson(defaultRun).path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PREVIEW","sampleSize":5001}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PREVIEW","sampleSize":100,"seed":-1}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/rule-runs/{runId}/execution", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.workbookVersionContentHash").isNotEmpty())
                .andExpect(jsonPath("$.compiledPlan.nodeCount").value(2))
                .andExpect(jsonPath("$.compiledPlan.nodes.length()").value(2))
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
        mockMvc.perform(put("/api/v1/rule-runs/{runId}/nodes/{nodeId}", runId, "relations:join_customer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeKind":"JOIN","status":"FAILED","errorMessage":"late failure"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/rule-runs/{runId}/nodes", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
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

        ObjectNode outputDocument = (ObjectNode) objectMapper.readTree(workbookDocument(
                datasetId, ordersTableId, customersTableId,
                orderIdFieldId, orderCustomerFieldId, customerIdFieldId));
        ((ObjectNode) outputDocument.path("sheets").get(2).path("rows").get(0))
                .put("enabled", false);
        ObjectNode outputSheet = ((ArrayNode) outputDocument.path("sheets")).addObject();
        outputSheet.put("key", "outputs");
        outputSheet.put("title", "输出");
        outputSheet.put("kind", "OUTPUTS");
        outputSheet.putArray("columns");
        ObjectNode outputRow = outputSheet.putArray("rows").addObject();
        outputRow.put("id", "materialize_csv");
        outputRow.put("enabled", true);
        ObjectNode outputCells = outputRow.putObject("cells");
        outputCells.set("input", objectMapper.createObjectNode()
                .put("type", "TABLE")
                .put("tableId", ordersTableId.toString())
                .put("alias", "orders"));
        outputCells.put("mode", "MATERIALIZE_VERSION");
        outputCells.put("format", "CSV");
        outputCells.put("name", "Materialized orders");
        ObjectNode outputWorkbookRequest = objectMapper.createObjectNode();
        outputWorkbookRequest.put("name", "OUTPUT execution guard");
        outputWorkbookRequest.put("datasetVersionId", datasetId.toString());
        outputWorkbookRequest.set("document", outputDocument);
        MvcResult outputWorkbookCreated = mockMvc.perform(post("/api/v1/rule-workbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outputWorkbookRequest.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        String outputWorkbookId = readJson(outputWorkbookCreated).path("id").asText();
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", outputWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/publish", outputWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", outputWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"PREVIEW\"}"))
                .andExpect(status().isBadRequest());
        MvcResult fullRun = mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", outputWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("FULL"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn();
        mockMvc.perform(post("/api/v1/rule-runs/{runId}/start",
                        readJson(fullRun).path("id").asText()))
                .andExpect(status().isConflict());
    }

    @Test
    void claimsPreviewRunWithLeaseAndFencesExpiredWorker() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "events.csv", "event_id,score\ne1,2\ne2,5\n");
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID tableId = findTable(dataset, "events");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID scoreColumnId = UUID.fromString(catalog.path("tables").get(0)
                .path("columns").get(1).path("columnId").asText());

        String workbookId = createWorkbook(
                typedCheckDocument(datasetId, tableId, scoreColumnId), datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/publish", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
        MvcResult runCreated = mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PREVIEW","sampleSize":100}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID runId = UUID.fromString(readJson(runCreated).path("id").asText());

        mockMvc.perform(post("/internal/rule-runs/claim")
                        .header("X-Rule-Worker-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-a","leaseSeconds":60,"mode":"PREVIEW"}
                                """))
                .andExpect(status().isUnauthorized());

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<MvcResult> firstRequest = executor.submit(() -> {
            start.await();
            return mockMvc.perform(post("/internal/rule-runs/claim")
                            .header("X-Rule-Worker-Token", "test-worker-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"worker-a","leaseSeconds":60,"mode":"PREVIEW"}
                                    """))
                    .andReturn();
        });
        Future<MvcResult> secondRequest = executor.submit(() -> {
            start.await();
            return mockMvc.perform(post("/internal/rule-runs/claim")
                            .header("X-Rule-Worker-Token", "test-worker-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"workerId":"worker-b","leaseSeconds":60,"mode":"PREVIEW"}
                                    """))
                    .andReturn();
        });
        start.countDown();
        JsonNode firstResult = readJson(firstRequest.get(10, TimeUnit.SECONDS));
        JsonNode secondResult = readJson(secondRequest.get(10, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertEquals(1, (firstResult.path("claimed").asBoolean() ? 1 : 0)
                + (secondResult.path("claimed").asBoolean() ? 1 : 0));
        JsonNode firstClaim = firstResult.path("claimed").asBoolean() ? firstResult : secondResult;
        assertEquals(runId.toString(), firstClaim.path("runId").asText());
        assertEquals(1, firstClaim.path("executionAttempt").asInt());
        String firstToken = firstClaim.path("claimToken").asText();

        mockMvc.perform(get("/api/v1/rule-runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedBy").isNotEmpty())
                .andExpect(jsonPath("$.leaseExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.executionAttempt").value(1))
                .andExpect(jsonPath("$.claimToken").doesNotExist());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/start", runId))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/internal/rule-runs/{runId}/execution", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", firstToken)
                        .header("X-Rule-Execution-Attempt", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mockMvc.perform(get("/internal/rule-runs/{runId}/execution-snapshot", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", firstToken)
                        .header("X-Rule-Execution-Attempt", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersionId").value(datasetId.toString()));

        mockMvc.perform(post("/internal/rule-runs/{runId}/start", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", firstToken)
                        .header("X-Rule-Execution-Attempt", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(put("/internal/rule-runs/{runId}/nodes/{nodeId}", runId, "custom_rules:check_score")
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", firstToken)
                        .header("X-Rule-Execution-Attempt", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeKind":"CHECK","status":"RUNNING"}
                                """))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE rule_runs SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                runId.toString());

        JsonNode secondClaim = readJson(mockMvc.perform(post("/internal/rule-runs/claim")
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-b","leaseSeconds":60,"mode":"PREVIEW"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.executionAttempt").value(2))
                .andReturn());
        String secondToken = secondClaim.path("claimToken").asText();

        mockMvc.perform(post("/internal/rule-runs/{runId}/lease", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", firstToken)
                        .header("X-Rule-Execution-Attempt", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseSeconds\":60}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/rule-runs/{runId}/nodes", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/internal/rule-runs/{runId}/lease", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", secondToken)
                        .header("X-Rule-Execution-Attempt", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionAttempt").value(2))
                .andExpect(jsonPath("$.leaseExpiresAt").isNotEmpty());

        mockMvc.perform(post("/internal/rule-runs/{runId}/start", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", secondToken)
                        .header("X-Rule-Execution-Attempt", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        for (String status : new String[] {"RUNNING", "SUCCEEDED"}) {
            mockMvc.perform(put("/internal/rule-runs/{runId}/nodes/{nodeId}",
                            runId, "custom_rules:check_score")
                            .header("X-Rule-Worker-Token", "test-worker-token")
                            .header("X-Rule-Claim-Token", secondToken)
                            .header("X-Rule-Execution-Attempt", 2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nodeKind\":\"CHECK\",\"status\":\"" + status + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/internal/rule-runs/{runId}/finish", runId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", secondToken)
                        .header("X-Rule-Execution-Attempt", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void cancelsClaimedRunFencesWorkerAndRetriesFrozenPreview() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "events.csv", "event_id,score\ne1,2\ne2,5\n");
        markReady(datasetId);
        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID tableId = findTable(dataset, "events");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID scoreColumnId = UUID.fromString(catalog.path("tables").get(0)
                .path("columns").get(1).path("columnId").asText());
        String workbookId = createWorkbook(
                typedCheckDocument(datasetId, tableId, scoreColumnId), datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/publish", workbookId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        MvcResult sourceCreated = mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PREVIEW","sampleSize":321,"seed":82}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode source = readJson(sourceCreated);
        UUID sourceId = UUID.fromString(source.path("id").asText());
        String claimJson = """
                {"workerId":"cancel-test-worker","leaseSeconds":60,"mode":"PREVIEW"}
                """;
        JsonNode claim = readJson(mockMvc.perform(post("/internal/rule-runs/claim")
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(sourceId.toString()))
                .andReturn());
        String claimToken = claim.path("claimToken").asText();
        String attempt = claim.path("executionAttempt").asText();
        mockMvc.perform(post("/internal/rule-runs/{runId}/start", sourceId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", claimToken)
                        .header("X-Rule-Execution-Attempt", attempt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        mockMvc.perform(put("/internal/rule-runs/{runId}/nodes/{nodeId}",
                        sourceId, "custom_rules:check_score")
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", claimToken)
                        .header("X-Rule-Execution-Attempt", attempt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeKind\":\"CHECK\",\"status\":\"RUNNING\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/cancel", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.claimedBy").doesNotExist())
                .andExpect(jsonPath("$.leaseExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
        mockMvc.perform(post("/api/v1/rule-runs/{runId}/cancel", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/rule-runs/{runId}/nodes", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

        mockMvc.perform(post("/internal/rule-runs/{runId}/lease", sourceId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", claimToken)
                        .header("X-Rule-Execution-Attempt", attempt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseSeconds\":60}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/internal/rule-runs/{runId}/nodes/{nodeId}",
                        sourceId, "custom_rules:check_score")
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", claimToken)
                        .header("X-Rule-Execution-Attempt", attempt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeKind\":\"CHECK\",\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/internal/rule-runs/{runId}/finish", sourceId)
                        .header("X-Rule-Worker-Token", "test-worker-token")
                        .header("X-Rule-Claim-Token", claimToken)
                        .header("X-Rule-Execution-Attempt", attempt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isConflict());

        JsonNode retry = readJson(mockMvc.perform(post("/api/v1/rule-runs/{runId}/retry", sourceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.mode").value("PREVIEW"))
                .andExpect(jsonPath("$.retryOfRunId").value(sourceId.toString()))
                .andExpect(jsonPath("$.sampleSize").value(321))
                .andExpect(jsonPath("$.seed").value(82))
                .andExpect(jsonPath("$.executionAttempt").value(0))
                .andReturn());
        UUID retryId = UUID.fromString(retry.path("id").asText());
        assertEquals(source.path("workbookVersionId").asText(), retry.path("workbookVersionId").asText());
        assertEquals(source.path("datasetVersionId").asText(), retry.path("datasetVersionId").asText());
        assertEquals(
                readJson(mockMvc.perform(get("/api/v1/rule-runs/{runId}/execution", sourceId))
                        .andExpect(status().isOk()).andReturn())
                        .path("workbookVersionContentHash").asText(),
                readJson(mockMvc.perform(get("/api/v1/rule-runs/{runId}/execution", retryId))
                        .andExpect(status().isOk()).andReturn())
                        .path("workbookVersionContentHash").asText());

        mockMvc.perform(post("/api/v1/rule-runs/{runId}/retry", retryId))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/rule-runs/{runId}/cancel", retryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/rule-runs/{runId}/retry", retryId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retryOfRunId").value(retryId.toString()))
                .andExpect(jsonPath("$.sampleSize").value(321))
                .andExpect(jsonPath("$.seed").value(82));

        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/runs", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renamesWorkbookAndSyncsDraftDocument() throws Exception {
        UUID datasetId = createDataset();
        ObjectNode document = objectMapper.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("datasetVersionId", datasetId.toString());
        document.putArray("sheets");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "原始规则库");
        request.put("datasetVersionId", datasetId.toString());
        request.set("document", document);

        MvcResult created = mockMvc.perform(post("/api/v1/rule-workbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        String workbookId = readJson(created).path("id").asText();

        ObjectNode rename = objectMapper.createObjectNode();
        rename.put("name", "清洗规则");
        rename.put("description", "订单数据清洗");

        mockMvc.perform(put("/api/v1/rule-workbooks/{id}", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rename.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("清洗规则"))
                .andExpect(jsonPath("$.description").value("订单数据清洗"))
                .andExpect(jsonPath("$.document.name").value("清洗规则"));

        mockMvc.perform(get("/api/v1/rule-workbooks/{id}", workbookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("清洗规则"))
                .andExpect(jsonPath("$.document.name").value("清洗规则"))
                .andExpect(jsonPath("$.description").value("订单数据清洗"));
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

    @Test
    void validatesTypedRuleNodesAndRejectsFreeformSqlPredicates() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "events.csv", "event_id,score\ne1,2\ne2,5\n");
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID tableId = findTable(dataset, "events");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID scoreColumnId = UUID.fromString(catalog.path("tables").get(0)
                .path("columns").get(1).path("columnId").asText());

        ObjectNode validDocument = typedCheckDocument(datasetId, tableId, scoreColumnId);
        String workbookId = createWorkbook(validDocument, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.nodeCount").value(1))
                .andExpect(jsonPath("$.compiledPlan.nodes[0].kind").value("CHECK"));

        ObjectNode freeformDocument = typedCheckDocument(datasetId, tableId, scoreColumnId);
        ObjectNode cells = (ObjectNode) freeformDocument.path("sheets").get(0)
                .path("rows").get(0).path("cells");
        cells.with("config").put("predicate", "score > 0; DROP TABLE events");
        String invalidWorkbookId = createWorkbook(freeformDocument, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", invalidWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("PREDICATE_REQUIRED"));
    }

    @Test
    void validatesStructuredDeriveExpressionsAndRejectsExecutableOperations() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "events.csv", "event_id,score\ne1,2\ne2,5\n");
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID tableId = findTable(dataset, "events");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID scoreColumnId = UUID.fromString(catalog.path("tables").get(0)
                .path("columns").get(1).path("columnId").asText());

        ObjectNode validDocument = typedCheckDocument(datasetId, tableId, scoreColumnId);
        ObjectNode row = (ObjectNode) validDocument.path("sheets").get(0).path("rows").get(0);
        row.put("id", "derive_score");
        ObjectNode cells = (ObjectNode) row.path("cells");
        cells.put("name", "分数翻倍");
        cells.put("nodeKind", "DERIVE");
        ObjectNode config = (ObjectNode) cells.path("config");
        config.removeAll();
        config.put("outputField", "score_x2");
        config.put("dataType", "DECIMAL");
        config.put("outputAlias", "events_with_score");
        ObjectNode arithmetic = config.putObject("expression");
        arithmetic.put("op", "ARITHMETIC");
        arithmetic.put("operator", "MULTIPLY");
        ObjectNode left = arithmetic.putObject("left");
        left.put("type", "FIELD");
        left.put("columnId", scoreColumnId.toString());
        left.put("nameSnapshot", "score");
        ObjectNode leftTable = left.putObject("tableRef");
        leftTable.put("alias", "input");
        leftTable.put("tableId", tableId.toString());
        ObjectNode right = arithmetic.putObject("right");
        right.put("type", "LITERAL");
        right.put("value", 2);

        ObjectNode copyRow = ((ObjectNode) validDocument.path("sheets").get(0))
                .withArray("rows").addObject();
        copyRow.put("id", "derive_score_copy");
        copyRow.put("enabled", true);
        ObjectNode copyCells = copyRow.putObject("cells");
        copyCells.put("name", "复用派生分数");
        copyCells.put("nodeKind", "DERIVE");
        ObjectNode copyInput = copyCells.putArray("inputs").addObject();
        copyInput.put("type", "NODE_OUTPUT");
        copyInput.put("nodeId", "custom_rules:derive_score");
        copyInput.put("outputAlias", "events_with_score");
        copyInput.put("alias", "events_with_score");
        ObjectNode copyConfig = copyCells.putObject("config");
        copyConfig.put("outputField", "score_x2_copy");
        copyConfig.put("dataType", "DECIMAL");
        copyConfig.put("outputAlias", "events_with_score_copy");
        ObjectNode copyExpression = copyConfig.putObject("expression");
        copyExpression.put("type", "FIELD");
        copyExpression.put("nameSnapshot", "score_x2");
        copyExpression.putObject("tableRef").put("alias", "events_with_score");

        String workbookId = createWorkbook(validDocument, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.compiledPlan.nodes[0].kind").value("DERIVE"))
                .andExpect(jsonPath("$.compiledPlan.nodes[1].kind").value("DERIVE"));

        ObjectNode duplicateOutputDocument = validDocument.deepCopy();
        ObjectNode duplicateOutputConfig = (ObjectNode) duplicateOutputDocument.path("sheets").get(0)
                .path("rows").get(1).path("cells").path("config");
        duplicateOutputConfig.put("outputField", "score_x2");
        String duplicateOutputWorkbookId = createWorkbook(duplicateOutputDocument, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", duplicateOutputWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("DERIVE_OUTPUT_FIELD_EXISTS")));

        ObjectNode invalidDocument = validDocument.deepCopy();
        ObjectNode invalidExpression = (ObjectNode) invalidDocument.path("sheets").get(0)
                .path("rows").get(0).path("cells").path("config").path("expression");
        invalidExpression.removeAll();
        invalidExpression.put("op", "SQL");
        invalidExpression.put("value", "score * 2; DROP TABLE events");
        String invalidWorkbookId = createWorkbook(invalidDocument, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", invalidWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("DERIVE_EXPRESSION_OP_INVALID")));
    }

    @Test
    void validatesAggregateOutputsAsReusableFieldsAndRejectsNameConflicts() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "events.csv", "event_id,category,score\ne1,a,2\ne2,a,5\ne3,b,7\n");
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID tableId = findTable(dataset, "events");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID categoryId = findCatalogColumnId(catalog, tableId, "category");
        UUID scoreId = findCatalogColumnId(catalog, tableId, "score");

        ObjectNode document = typedCheckDocument(datasetId, tableId, scoreId);
        ObjectNode sheet = (ObjectNode) document.path("sheets").get(0);
        ObjectNode aggregate = (ObjectNode) sheet.path("rows").get(0);
        aggregate.put("id", "aggregate_events");
        ObjectNode aggregateCells = (ObjectNode) aggregate.path("cells");
        aggregateCells.put("name", "按类别汇总");
        aggregateCells.put("nodeKind", "AGGREGATE");
        ObjectNode aggregateConfig = (ObjectNode) aggregateCells.path("config");
        aggregateConfig.removeAll();
        aggregateConfig.put("outputAlias", "category_summary");
        ObjectNode categoryRef = objectMapper.createObjectNode();
        categoryRef.put("type", "FIELD");
        categoryRef.put("columnId", categoryId.toString());
        categoryRef.put("nameSnapshot", "category");
        categoryRef.putObject("tableRef").put("alias", "input").put("tableId", tableId.toString());
        aggregateConfig.putArray("groupBy").add(categoryRef);
        ObjectNode count = aggregateConfig.putArray("metrics").addObject();
        count.put("name", "record_count");
        count.put("op", "COUNT");
        count.put("field", "*");
        ObjectNode sum = aggregateConfig.withArray("metrics").addObject();
        sum.put("name", "score_total");
        sum.put("op", "SUM");
        ObjectNode scoreRef = objectMapper.createObjectNode();
        scoreRef.put("type", "FIELD");
        scoreRef.put("columnId", scoreId.toString());
        scoreRef.put("nameSnapshot", "score");
        scoreRef.putObject("tableRef").put("alias", "input").put("tableId", tableId.toString());
        sum.set("field", scoreRef);

        ObjectNode derive = sheet.withArray("rows").addObject();
        derive.put("id", "derive_count_copy");
        derive.put("enabled", true);
        ObjectNode deriveCells = derive.putObject("cells");
        deriveCells.put("name", "引用聚合指标");
        deriveCells.put("nodeKind", "DERIVE");
        ObjectNode deriveInput = deriveCells.putArray("inputs").addObject();
        deriveInput.put("type", "NODE_OUTPUT");
        deriveInput.put("nodeId", "custom_rules:aggregate_events");
        deriveInput.put("outputAlias", "category_summary");
        deriveInput.put("alias", "category_summary");
        ObjectNode deriveConfig = deriveCells.putObject("config");
        deriveConfig.put("outputField", "count_copy");
        deriveConfig.put("dataType", "INTEGER");
        deriveConfig.put("outputAlias", "category_summary_copy");
        ObjectNode countExpression = deriveConfig.putObject("expression");
        countExpression.put("type", "FIELD");
        countExpression.put("nameSnapshot", "record_count");
        countExpression.putObject("tableRef").put("alias", "category_summary");

        String workbookId = createWorkbook(document, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.compiledPlan.nodes[0].kind").value("AGGREGATE"))
                .andExpect(jsonPath("$.compiledPlan.nodes[1].kind").value("DERIVE"));

        ObjectNode conflicting = document.deepCopy();
        ((ObjectNode) conflicting.path("sheets").get(0).path("rows").get(0)
                .path("cells").path("config").path("metrics").get(1)).put("name", "category");
        String conflictingWorkbookId = createWorkbook(conflicting, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", conflictingWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("AGGREGATE_METRIC_NAME_CONFLICT")));

        ObjectNode duplicate = document.deepCopy();
        ((ObjectNode) duplicate.path("sheets").get(0).path("rows").get(0)
                .path("cells").path("config").path("metrics").get(1)).put("name", "record_count");
        String duplicateWorkbookId = createWorkbook(duplicate, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", duplicateWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("AGGREGATE_METRIC_NAME_DUPLICATE")));

        ObjectNode duplicateGroup = document.deepCopy();
        ArrayNode duplicateGroupBy = (ArrayNode) duplicateGroup.path("sheets").get(0).path("rows").get(0)
                .path("cells").path("config").path("groupBy");
        duplicateGroupBy.add(duplicateGroupBy.get(0).deepCopy());
        String duplicateGroupWorkbookId = createWorkbook(duplicateGroup, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", duplicateGroupWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("AGGREGATE_GROUP_BY_DUPLICATE")));

        ObjectNode missingMetricField = document.deepCopy();
        ((ObjectNode) missingMetricField.path("sheets").get(0).path("rows").get(0)
                .path("cells").path("config").path("metrics").get(1)).remove("field");
        String missingMetricFieldWorkbookId = createWorkbook(missingMetricField, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", missingMetricFieldWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("AGGREGATE_METRIC_FIELD_REQUIRED")));

        ObjectNode droppedField = document.deepCopy();
        ObjectNode droppedExpression = (ObjectNode) droppedField.path("sheets").get(0).path("rows").get(1)
                .path("cells").path("config").path("expression");
        droppedExpression.put("columnId", scoreId.toString());
        droppedExpression.put("nameSnapshot", "score");
        droppedExpression.with("tableRef").put("tableId", tableId.toString());
        String droppedFieldWorkbookId = createWorkbook(droppedField, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", droppedFieldWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("DERIVE_FIELD_INVALID")));
    }

    @Test
    void validatesJoinInputsAndPropagatesBothSidesForDownstreamFields() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "orders.csv", "order_id,customer_id,total\no1,c1,20\no2,c2,30\n");
        addCsv(datasetId, "customers.csv", "customer_id,city\nc1,Oslo\nc2,Tokyo\n");
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID ordersTableId = findTable(dataset, "orders");
        UUID customersTableId = findTable(dataset, "customers");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID orderCustomerId = findCatalogColumnId(catalog, ordersTableId, "customer_id");
        UUID customerId = findCatalogColumnId(catalog, customersTableId, "customer_id");
        UUID customerCityId = findCatalogColumnId(catalog, customersTableId, "city");
        UUID ordersTotalId = findCatalogColumnId(catalog, ordersTableId, "total");

        ObjectNode document = typedCheckDocument(datasetId, ordersTableId, ordersTotalId);
        ObjectNode sheet = (ObjectNode) document.path("sheets").get(0);
        ObjectNode join = sheet.withArray("rows").addObject();
        join.put("id", "join_customers");
        join.put("enabled", true);
        ObjectNode joinCells = join.putObject("cells");
        joinCells.put("name", "关联客户信息");
        joinCells.put("nodeKind", "JOIN");
        joinCells.putArray("inputs").addObject()
                .put("type", "TABLE").put("tableId", ordersTableId.toString()).put("alias", "left");
        joinCells.withArray("inputs").addObject()
                .put("type", "TABLE").put("tableId", customersTableId.toString()).put("alias", "right");
        ObjectNode joinConfig = joinCells.putObject("config");
        joinConfig.put("leftAlias", "left");
        joinConfig.put("rightAlias", "right");
        joinConfig.put("joinType", "LEFT");
        joinConfig.put("cardinality", "N:1");
        joinConfig.put("duplicatePolicy", "FAIL");
        joinConfig.put("outputAlias", "orders_with_customers");
        ObjectNode leftKey = joinConfig.putArray("leftKeys").addObject();
        leftKey.put("type", "FIELD");
        leftKey.put("columnId", orderCustomerId.toString());
        leftKey.put("nameSnapshot", "customer_id");
        leftKey.putObject("tableRef").put("alias", "left").put("tableId", ordersTableId.toString());
        ObjectNode rightKey = joinConfig.putArray("rightKeys").addObject();
        rightKey.put("type", "FIELD");
        rightKey.put("columnId", customerId.toString());
        rightKey.put("nameSnapshot", "customer_id");
        rightKey.putObject("tableRef").put("alias", "right").put("tableId", customersTableId.toString());
        joinConfig.putObject("guards").put("rightKeyUnique", true).put("maxFanout", 1).put("minMatchRate", 0.8);
        ((ObjectNode) joinConfig.path("guards")).put("onViolation", "FAIL_RUN");

        ObjectNode derive = sheet.withArray("rows").addObject();
        derive.put("id", "derive_city_copy");
        derive.put("enabled", true);
        ObjectNode deriveCells = derive.putObject("cells");
        deriveCells.put("name", "从关联结果读取城市");
        deriveCells.put("nodeKind", "DERIVE");
        deriveCells.putArray("inputs").addObject()
                .put("type", "NODE_OUTPUT")
                .put("nodeId", "custom_rules:join_customers")
                .put("outputAlias", "orders_with_customers")
                .put("alias", "orders_with_customers");
        ObjectNode deriveConfig = deriveCells.putObject("config");
        deriveConfig.put("outputField", "city_copy");
        deriveConfig.put("dataType", "STRING");
        deriveConfig.put("outputAlias", "orders_with_city");
        ObjectNode expression = deriveConfig.putObject("expression");
        expression.put("type", "FIELD");
        expression.put("columnId", customerCityId.toString());
        expression.put("nameSnapshot", "city");
        expression.putObject("tableRef").put("alias", "orders_with_customers");

        String workbookId = createWorkbook(document, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.compiledPlan.nodes[1].kind").value("JOIN"))
                .andExpect(jsonPath("$.compiledPlan.nodes[2].kind").value("DERIVE"))
                .andExpect(jsonPath("$.compiledPlan.edges[0].from").value("custom_rules:join_customers"))
                .andExpect(jsonPath("$.compiledPlan.edges[0].to").value("custom_rules:derive_city_copy"));

        ObjectNode invalidJoin = document.deepCopy();
        ObjectNode invalidLeftKey = (ObjectNode) invalidJoin.path("sheets").get(0).path("rows").get(1)
                .path("cells").path("config").path("leftKeys").get(0);
        invalidLeftKey.put("columnId", customerId.toString());
        invalidLeftKey.put("nameSnapshot", "customer_id");
        invalidLeftKey.with("tableRef").put("tableId", customersTableId.toString());
        String invalidWorkbookId = createWorkbook(invalidJoin, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", invalidWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("JOIN_LEFT_KEY_INVALID")));

        ObjectNode unsupportedDuplicatePolicy = document.deepCopy();
        ((ObjectNode) unsupportedDuplicatePolicy.path("sheets").get(0).path("rows").get(1)
                .path("cells").path("config")).put("duplicatePolicy", "KEEP_FIRST");
        String unsupportedPolicyWorkbookId = createWorkbook(unsupportedDuplicatePolicy, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", unsupportedPolicyWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("JOIN_DUPLICATE_POLICY_UNSUPPORTED")));

        ObjectNode invalidGuard = document.deepCopy();
        ((ObjectNode) invalidGuard.path("sheets").get(0).path("rows").get(1)
                .path("cells").path("config").path("guards")).put("minMatchRate", 1.5);
        String invalidGuardWorkbookId = createWorkbook(invalidGuard, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", invalidGuardWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("JOIN_MIN_MATCH_RATE_INVALID")));
    }

    @Test
    void validatesNodeOutputDagAndRejectsFieldsDroppedByUpstreamProject() throws Exception {
        UUID datasetId = createDataset();
        addCsv(datasetId, "events.csv", "event_id,score\ne1,2\ne2,5\n");
        markReady(datasetId);

        JsonNode dataset = readJson(mockMvc.perform(get("/api/v1/datasets/{id}", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID tableId = findTable(dataset, "events");
        JsonNode catalog = readJson(mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetId))
                .andExpect(status().isOk())
                .andReturn());
        UUID eventIdColumnId = UUID.fromString(catalog.path("tables").get(0)
                .path("columns").get(0).path("columnId").asText());
        UUID scoreColumnId = UUID.fromString(catalog.path("tables").get(0)
                .path("columns").get(1).path("columnId").asText());

        ObjectNode dag = typedCheckDocument(datasetId, tableId, scoreColumnId);
        ObjectNode checkCells = (ObjectNode) dag.path("sheets").get(0).path("rows").get(0).path("cells");
        checkCells.with("config").put("outputAlias", "checked_rows");
        ObjectNode sheet = (ObjectNode) dag.path("sheets").get(0);
        ObjectNode project = sheet.withArray("rows").addObject();
        project.put("id", "project_event");
        project.put("enabled", true);
        ObjectNode projectCells = project.putObject("cells");
        projectCells.put("name", "保留事件编号");
        projectCells.put("nodeKind", "PROJECT");
        ObjectNode projectInput = projectCells.putArray("inputs").addObject();
        projectInput.put("type", "NODE_OUTPUT");
        projectInput.put("nodeId", "custom_rules:check_score");
        projectInput.put("outputAlias", "checked_rows");
        projectInput.put("alias", "checked_rows");
        ObjectNode projectConfig = projectCells.putObject("config");
        projectConfig.put("outputAlias", "projected_rows");
        ObjectNode selectedId = objectMapper.createObjectNode();
        selectedId.put("type", "FIELD");
        selectedId.put("columnId", eventIdColumnId.toString());
        selectedId.put("nameSnapshot", "event_id");
        ObjectNode selectedTable = selectedId.putObject("tableRef");
        selectedTable.put("alias", "checked_rows");
        selectedTable.put("tableId", tableId.toString());
        projectConfig.putArray("select").add(selectedId);

        String workbookId = createWorkbook(dag, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", workbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.nodeCount").value(2))
                .andExpect(jsonPath("$.compiledPlan.edges.length()").value(1))
                .andExpect(jsonPath("$.compiledPlan.edges[0].from").value("custom_rules:check_score"))
                .andExpect(jsonPath("$.compiledPlan.edges[0].to").value("custom_rules:project_event"));

        ObjectNode invalidDag = dag.deepCopy();
        ObjectNode invalidSheet = (ObjectNode) invalidDag.path("sheets").get(0);
        ObjectNode filter = invalidSheet.withArray("rows").addObject();
        filter.put("id", "filter_dropped_score");
        filter.put("enabled", true);
        ObjectNode filterCells = filter.putObject("cells");
        filterCells.put("name", "引用已移除分数");
        filterCells.put("nodeKind", "FILTER");
        ObjectNode filterInput = filterCells.putArray("inputs").addObject();
        filterInput.put("type", "NODE_OUTPUT");
        filterInput.put("nodeId", "custom_rules:project_event");
        filterInput.put("outputAlias", "projected_rows");
        filterInput.put("alias", "projected_rows");
        ObjectNode filterConfig = filterCells.putObject("config");
        filterConfig.put("outputAlias", "filtered_rows");
        ObjectNode filterPredicate = filterConfig.putObject("predicate");
        filterPredicate.put("op", "AND");
        ObjectNode scoreCondition = filterPredicate.putArray("predicates").addObject();
        scoreCondition.put("op", "GT");
        ObjectNode scoreField = scoreCondition.putObject("field");
        scoreField.put("type", "FIELD");
        scoreField.put("columnId", scoreColumnId.toString());
        scoreField.put("nameSnapshot", "score");
        ObjectNode scoreTable = scoreField.putObject("tableRef");
        scoreTable.put("alias", "projected_rows");
        scoreTable.put("tableId", tableId.toString());
        scoreCondition.put("value", 0);
        scoreCondition.putArray("args").add(scoreField.deepCopy());

        String invalidWorkbookId = createWorkbook(invalidDag, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", invalidWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("PREDICATE_FIELD_INVALID")));

        ObjectNode cycle = dag.deepCopy();
        ObjectNode cycleCheck = (ObjectNode) cycle.path("sheets").get(0)
                .path("rows").get(0).path("cells");
        ObjectNode cycleInput = (ObjectNode) cycleCheck.withArray("inputs").get(0);
        cycleInput.put("type", "NODE_OUTPUT");
        cycleInput.put("nodeId", "custom_rules:project_event");
        cycleInput.put("outputAlias", "projected_rows");
        cycleInput.put("alias", "projected_rows");
        ObjectNode checkField = (ObjectNode) cycleCheck.path("config")
                .path("predicate").path("predicates").get(0).path("field");
        checkField.with("tableRef").put("alias", "projected_rows");
        String cycleWorkbookId = createWorkbook(cycle, datasetId);
        mockMvc.perform(post("/api/v1/rule-workbooks/{id}/validate", cycleWorkbookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.issues[*].code",
                        org.hamcrest.Matchers.hasItem("DEPENDENCY_CYCLE")));
    }

    private String createWorkbook(ObjectNode document, UUID datasetVersionId) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("name", "结构化规则校验 " + UUID.randomUUID());
        request.put("datasetVersionId", datasetVersionId.toString());
        request.set("document", document);
        MvcResult result = mockMvc.perform(post("/api/v1/rule-workbooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return readJson(result).path("id").asText();
    }

    private ObjectNode typedCheckDocument(UUID datasetId, UUID tableId, UUID columnId) {
        ObjectNode document = objectMapper.createObjectNode();
        document.put("schemaVersion", 1);
        document.put("datasetVersionId", datasetId.toString());
        ObjectNode sheet = document.putArray("sheets").addObject();
        sheet.put("key", "custom_rules");
        sheet.put("title", "结构化规则");
        sheet.put("kind", "CUSTOM_RULES");
        sheet.putArray("columns");
        ObjectNode row = sheet.putArray("rows").addObject();
        row.put("id", "check_score");
        row.put("enabled", true);
        ObjectNode cells = row.putObject("cells");
        cells.put("name", "分数大于零");
        cells.put("nodeKind", "CHECK");
        ObjectNode input = cells.putArray("inputs").addObject();
        input.put("type", "TABLE");
        input.put("tableId", tableId.toString());
        input.put("alias", "input");
        ObjectNode config = cells.putObject("config");
        config.put("scope", "ROW");
        config.put("effect", "WARN");
        ObjectNode predicate = config.putObject("predicate");
        predicate.put("op", "AND");
        ObjectNode child = predicate.putArray("predicates").addObject();
        child.put("op", "GT");
        ObjectNode field = child.putObject("field");
        field.put("type", "FIELD");
        field.put("columnId", columnId.toString());
        field.put("nameSnapshot", "score");
        ObjectNode tableRef = field.putObject("tableRef");
        tableRef.put("alias", "input");
        tableRef.put("tableId", tableId.toString());
        child.put("value", 0);
        child.putArray("args").add(field.deepCopy());
        return document;
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

    private UUID findCatalogColumnId(JsonNode catalog, UUID tableId, String columnName) {
        for (JsonNode table : catalog.path("tables")) {
            if (!tableId.toString().equals(table.path("tableId").asText())) continue;
            for (JsonNode column : table.path("columns")) {
                if (columnName.equals(column.path("name").asText())) {
                    return UUID.fromString(column.path("columnId").asText());
                }
            }
        }
        throw new AssertionError("Column not found: " + columnName);
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
