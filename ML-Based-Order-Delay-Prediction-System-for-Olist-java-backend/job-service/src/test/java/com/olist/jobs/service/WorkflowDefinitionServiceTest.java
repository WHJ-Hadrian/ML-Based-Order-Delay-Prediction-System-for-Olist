package com.olist.jobs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.api.WorkflowRequest;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionServiceTest {
    @Mock private WorkflowDefinitionRepository repository;
    private WorkflowDefinitionService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() { service = new WorkflowDefinitionService(repository, mapper); }

    @Test
    void persistsGenericAcyclicWorkflowForOwner() throws Exception {
        when(repository.save(any(WorkflowDefinition.class))).thenAnswer(call -> call.getArgument(0));
        var request = request("Reusable", "[{\"id\":\"source\",\"type\":\"DATA_SOURCE\",\"position\":{\"x\":0,\"y\":0},\"data\":{}},"
                + "{\"id\":\"clean\",\"type\":\"FILTER\",\"position\":{\"x\":280,\"y\":0},\"data\":{}}]",
                "[{\"id\":\"e1\",\"source\":\"source\",\"target\":\"clean\",\"sourceHandle\":\"output\",\"targetHandle\":\"input\"}]");

        var response = service.create("owner-a", request);

        assertEquals("Reusable", response.name());
        assertEquals(1, response.revision());
        assertEquals(2, response.document().path("nodes").size());
        verify(repository).save(any(WorkflowDefinition.class));
    }

    @Test
    void rejectsCyclesDuplicateEdgesUnknownTypesAndExecutablePayloads() throws Exception {
        var cycle = request("cycle", "[{\"id\":\"a\",\"type\":\"DATA_SOURCE\",\"position\":{\"x\":0,\"y\":0},\"data\":{}},"
                + "{\"id\":\"b\",\"type\":\"FILTER\",\"position\":{\"x\":0,\"y\":0},\"data\":{}}]",
                "[{\"id\":\"e1\",\"source\":\"a\",\"target\":\"b\"},{\"id\":\"e2\",\"source\":\"b\",\"target\":\"a\"}]");
        var duplicateEdge = request("duplicate", "[{\"id\":\"a\",\"type\":\"DATA_SOURCE\",\"position\":{\"x\":0,\"y\":0},\"data\":{}},"
                + "{\"id\":\"b\",\"type\":\"OUTPUT\",\"position\":{\"x\":0,\"y\":0},\"data\":{}}]",
                "[{\"id\":\"e1\",\"source\":\"a\",\"target\":\"b\"},{\"id\":\"e2\",\"source\":\"a\",\"target\":\"b\"}]");
        var script = mapper.readTree("{\"nodes\":[],\"edges\":[],\"sql\":\"select 1\"}");
        var unknown = request("unknown", "[{\"id\":\"x\",\"type\":\"CUSTOM\",\"position\":{\"x\":0,\"y\":0},\"data\":{}}]", "[]");

        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", cycle));
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", duplicateEdge));
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", new WorkflowRequest("script", script, null)));
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", unknown));
        verify(repository, never()).save(any(WorkflowDefinition.class));
    }

    @Test
    void rejectsStaleUpdatesAndDoesNotExposeOtherOwners() throws Exception {
        UUID id = UUID.randomUUID();
        var existing = new WorkflowDefinition(id, "owner-a", "Flow", 2,
                "{\"nodes\":[],\"edges\":[]}", Instant.now(), Instant.now());
        when(repository.lockByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(existing));
        when(repository.findByIdAndOwnerId(id, "owner-b")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.update("owner-a", id,
                new WorkflowRequest("Flow", mapper.readTree("{\"nodes\":[],\"edges\":[]}"), 1L)));
        assertThrows(ResponseStatusException.class, () -> service.get("owner-b", id));
        verify(repository, never()).save(any(WorkflowDefinition.class));
    }

    @Test
    void acceptsCompleteDatasetTableReferenceAndRejectsPartialReference() throws Exception {
        when(repository.save(any(WorkflowDefinition.class))).thenAnswer(call -> call.getArgument(0));
        String validConfig = "{\"datasetId\":\"550e8400-e29b-41d4-a716-446655440000\","
                + "\"datasetVersionId\":\"550e8400-e29b-41d4-a716-446655440001\","
                + "\"versionNo\":2,\"tableId\":\"550e8400-e29b-41d4-a716-446655440002\","
                + "\"tableName\":\"customers\",\"rowCount\":100,\"schemaHash\":\"abc123\"}";
        var valid = request("source", "[{\"id\":\"source\",\"type\":\"DATA_SOURCE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":" + validConfig + "}}]", "[]");
        var partial = request("source", "[{\"id\":\"source\",\"type\":\"DATA_SOURCE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":{\"datasetVersionId\":\"not-a-uuid\"}}}]", "[]");
        var childWithoutParent = request("source", "[{\"id\":\"source\",\"type\":\"DATA_SOURCE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":{\"tableId\":\"550e8400-e29b-41d4-a716-446655440002\"}}}]", "[]");
        var datasetDraft = request("source", "[{\"id\":\"source\",\"type\":\"DATA_SOURCE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":{\"datasetId\":\"550e8400-e29b-41d4-a716-446655440000\"}}}]", "[]");

        var response = service.create("owner-a", valid);
        var draftResponse = service.create("owner-a", datasetDraft);

        assertEquals("customers", response.document().path("nodes").get(0).path("data")
                .path("config").path("tableName").asText());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", draftResponse.document()
                .path("nodes").get(0).path("data").path("config").path("datasetId").asText());
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", partial));
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", childWithoutParent));
    }

    @Test
    void validatesRuleVersionReferenceWithoutAllowingExecutableConfiguration() throws Exception {
        when(repository.save(any(WorkflowDefinition.class))).thenAnswer(call -> call.getArgument(0));
        var valid = request("rules", "[{\"id\":\"rule\",\"type\":\"RULE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":{"
                + "\"ruleVersionId\":\"550e8400-e29b-41d4-a716-446655440000\","
                + "\"workbookId\":\"550e8400-e29b-41d4-a716-446655440001\","
                + "\"versionNumber\":1,\"workbookName\":\"清洗规则\",\"contentHash\":\"abc\"}}}]", "[]");
        var invalid = request("rules", "[{\"id\":\"rule\",\"type\":\"RULE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":{"
                + "\"ruleVersionId\":\"not-a-uuid\",\"versionNumber\":0}}}]", "[]");

        assertEquals(1, service.create("owner-a", valid).document().path("nodes").size());
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", invalid));
    }

    private WorkflowRequest request(String name, String nodes, String edges) throws Exception {
        return new WorkflowRequest(name, mapper.readTree("{\"version\":1,\"nodes\":" + nodes + ",\"edges\":" + edges + "}"), null);
    }
}
