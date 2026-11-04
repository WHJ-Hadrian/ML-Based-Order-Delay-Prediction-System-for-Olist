package com.olist.datasets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
class AnalysisWorkspaceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM analysis_workspaces");
    }

    @Test
    void persistsWorkspaceAndScopesEveryOperationToOwner() throws Exception {
        UUID datasetVersionId = createReadyDataset();
        var catalogResult = mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetVersionId)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk())
                .andReturn();
        var table = objectMapper.readTree(catalogResult.getResponse().getContentAsString())
                .path("tables").get(0);
        UUID tableId = UUID.fromString(table.path("tableId").asText());
        UUID columnId = UUID.fromString(table.path("columns").get(0).path("columnId").asText());
        String createRequest = request("收入分析", null, true, datasetVersionId, tableId, columnId);
        var created = mockMvc.perform(post("/api/v1/analysis-workspaces")
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.cards[0].datasetVersionId").value(datasetVersionId.toString()))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

        mockMvc.perform(get("/api/v1/analysis-workspaces/{id}", id)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("收入分析"))
                .andExpect(jsonPath("$.cards.length()").value(1));

        mockMvc.perform(get("/api/v1/analysis-workspaces")
                        .with(jwt().jwt(token -> token.subject("owner-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/v1/analysis-workspaces/{id}", id)
                        .with(jwt().jwt(token -> token.subject("owner-b"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/analysis-workspaces/{id}", id)
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("收入分析 v2", 1, true, datasetVersionId, tableId, columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.name").value("收入分析 v2"));

        mockMvc.perform(put("/api/v1/analysis-workspaces/{id}", id)
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("旧版本覆盖", 1, true, datasetVersionId, tableId, columnId)))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/analysis-workspaces/{id}", id)
                        .with(jwt().jwt(token -> token.subject("owner-b"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/analysis-workspaces/{id}", id)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void copiesWorkspaceCardsIntoAnIndependentOwnerScopedWorkspace() throws Exception {
        UUID datasetVersionId = createReadyDataset();
        var catalogResult = mockMvc.perform(get("/api/v1/datasets/{id}/catalog", datasetVersionId)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk())
                .andReturn();
        var table = objectMapper.readTree(catalogResult.getResponse().getContentAsString())
                .path("tables").get(0);
        UUID tableId = UUID.fromString(table.path("tableId").asText());
        UUID columnId = UUID.fromString(table.path("columns").get(0).path("columnId").asText());
        var created = mockMvc.perform(post("/api/v1/analysis-workspaces")
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("收入分析", null, true, datasetVersionId, tableId, columnId)))
                .andExpect(status().isCreated())
                .andReturn();
        String sourceId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

        var copied = mockMvc.perform(post("/api/v1/analysis-workspaces/{id}/copy", sourceId)
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"收入分析 副本\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("收入分析 副本"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.cards[0].datasetVersionId").value(datasetVersionId.toString()))
                .andExpect(jsonPath("$.cards[0].tableId").value(tableId.toString()))
                .andExpect(jsonPath("$.cards[0].columnId").value(columnId.toString()))
                .andReturn();
        String copyId = objectMapper.readTree(copied.getResponse().getContentAsString()).path("id").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(sourceId, copyId);

        mockMvc.perform(put("/api/v1/analysis-workspaces/{id}", copyId)
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("收入分析 副本", 1, false, datasetVersionId, tableId, columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.cards").isEmpty());

        mockMvc.perform(get("/api/v1/analysis-workspaces/{id}", sourceId)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("收入分析"))
                .andExpect(jsonPath("$.cards.length()").value(1));
        mockMvc.perform(post("/api/v1/analysis-workspaces/{id}/copy", sourceId)
                        .with(jwt().jwt(token -> token.subject("owner-b")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权副本\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/analysis-workspaces/{id}/copy", sourceId)
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"收入分析 副本\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsDuplicateNamesAndMoreThanTwentyCards() throws Exception {
        UUID datasetVersionId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID columnId = UUID.randomUUID();
        var ownerA = jwt().jwt(token -> token.subject("owner-a"));
        mockMvc.perform(post("/api/v1/analysis-workspaces")
                        .with(ownerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("复用名称", null, false, datasetVersionId, tableId, columnId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/analysis-workspaces")
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("复用名称", null, false, datasetVersionId, tableId, columnId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/analysis-workspaces")
                        .with(jwt().jwt(token -> token.subject("owner-b")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manyCardsRequest()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCardsThatReferenceDataOutsideTheOwnersReadyCatalog() throws Exception {
        UUID datasetVersionId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/analysis-workspaces")
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("越权引用", null, true, datasetVersionId, UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    private String request(
            String name, Integer revision, boolean includeCard,
            UUID datasetVersionId, UUID tableId, UUID columnId)
            throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("name", name);
        if (revision != null) body.put("expectedRevision", revision);
        var cards = body.putArray("cards");
        if (includeCard) {
            var card = cards.addObject();
            card.put("datasetVersionId", datasetVersionId.toString());
            card.put("tableId", tableId.toString());
            card.put("columnId", columnId.toString());
        }
        return objectMapper.writeValueAsString(body);
    }

    private String manyCardsRequest() throws Exception {
        var body = objectMapper.createObjectNode();
        body.put("name", "过量卡片");
        var cards = body.putArray("cards");
        for (int index = 0; index < 21; index++) {
            var card = cards.addObject();
            card.put("datasetVersionId", UUID.randomUUID().toString());
            card.put("tableId", UUID.randomUUID().toString());
            card.put("columnId", UUID.randomUUID().toString());
        }
        return objectMapper.writeValueAsString(body);
    }

    private UUID createReadyDataset() throws Exception {
        var created = mockMvc.perform(post("/api/v1/datasets")
                        .with(jwt().jwt(token -> token.subject("owner-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"workspace-test-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText());
        MockMultipartFile file = new MockMultipartFile(
                "file", "metrics.csv", "text/csv",
                "segment,amount\\nretail,10\\nwholesale,20\\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/datasets/{id}/files", id)
                        .file(file)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/datasets/{id}/ready", id)
                        .with(jwt().jwt(token -> token.subject("owner-a"))))
                .andExpect(status().isOk());
        return id;
    }
}
