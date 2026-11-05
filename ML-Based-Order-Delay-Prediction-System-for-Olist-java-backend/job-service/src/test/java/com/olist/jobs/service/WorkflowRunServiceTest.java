package com.olist.jobs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.api.WorkflowRunRequest;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import com.olist.jobs.workflow.WorkflowRunStarter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkflowRunServiceTest {
    @Mock private WorkflowDefinitionRepository workflows;
    @Mock private WorkflowRunRepository runs;
    @Mock private WorkflowRunNodeRepository nodes;
    @Mock private WorkflowRunStarter starter;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    private WorkflowRunService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new WorkflowRunService(workflows, runs, nodes, starter, mapper, transactionManager);
    }

    @Test
    void refusesUnimplementedNodeTypesBeforeCreatingPersistentRuns() throws Exception {
        UUID id = UUID.randomUUID();
        var definition = definition(id, "[{\"id\":\"source\",\"type\":\"DATA_SOURCE\","
                + "\"position\":{\"x\":0,\"y\":0},\"data\":{\"config\":{"
                + "\"datasetVersionId\":\"550e8400-e29b-41d4-a716-446655440001\","
                + "\"tableId\":\"550e8400-e29b-41d4-a716-446655440002\"}}},"
                + "{\"id\":\"train\",\"type\":\"TRAIN_MODEL\",\"position\":{\"x\":1,\"y\":1},\"data\":{}}]");
        when(workflows.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(definition));

        var exception = assertThrows(ResponseStatusException.class,
                () -> service.start("owner-a", id, new WorkflowRunRequest(3L)));

        assertEquals(400, exception.getStatusCode().value());
        verify(runs, never()).saveAndFlush(any());
        verify(starter, never()).start(any());
    }

    @Test
    void refusesRunsFromStaleRevisionsAndForeignOwners() {
        UUID id = UUID.randomUUID();
        when(workflows.findByIdAndOwnerId(id, "owner-a"))
                .thenReturn(Optional.of(new WorkflowDefinition(id, "owner-a", "Source", 3,
                        "{\"nodes\":[],\"edges\":[]}", Instant.now(), Instant.now())));
        when(workflows.findByIdAndOwnerId(id, "owner-b")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.start("owner-a", id, new WorkflowRunRequest(2L)));
        assertThrows(ResponseStatusException.class,
                () -> service.start("owner-b", id, new WorkflowRunRequest(3L)));
        verify(runs, never()).saveAndFlush(any());
    }

    private WorkflowDefinition definition(UUID id, String nodesJson) throws Exception {
        Instant now = Instant.now();
        String document = "{\"version\":1,\"nodes\":" + nodesJson + ",\"edges\":[]}";
        return new WorkflowDefinition(id, "owner-a", "Source", 3, document, now, now);
    }
}
