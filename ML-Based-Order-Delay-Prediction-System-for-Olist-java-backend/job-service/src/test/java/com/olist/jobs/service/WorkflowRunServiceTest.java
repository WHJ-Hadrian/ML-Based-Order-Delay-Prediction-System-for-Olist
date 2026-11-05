package com.olist.jobs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.api.WorkflowRunRequest;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.entity.WorkflowRunNode;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import com.olist.jobs.workflow.WorkflowRunStarter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void returnsPersistedNodeLogsWithRunDetails() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(runId, UUID.randomUUID(), "owner-a", "Preview", 2,
                "{\"nodes\":[],\"edges\":[]}", null, now);
        run.start(now);
        WorkflowRunNode node = new WorkflowRunNode(UUID.randomUUID(), runId, "source", "DATA_SOURCE", now);
        node.setLogsJson("[{\"timestamp\":1762430400000,\"level\":\"INFO\",\"message\":\"节点开始执行\"}]");
        when(runs.findByIdAndOwnerId(runId, "owner-a")).thenReturn(Optional.of(run));
        when(nodes.findByRunIdOrderByCreatedAtAsc(runId)).thenReturn(java.util.List.of(node));

        var response = service.get("owner-a", runId);

        assertEquals(1, response.nodes().get(0).logs().size());
        assertEquals("节点开始执行", response.nodes().get(0).logs().get(0).message());
        assertEquals("INFO", response.nodes().get(0).logs().get(0).level());
    }

    @Test
    void refusesFilterValuesOutsideTheValidatedContractBeforePersisting() throws Exception {
        UUID id = UUID.randomUUID();
        var definition = definition(id, "{\"version\":1,\"nodes\":["
                + sourceNode() + ","
                + "{\"id\":\"filter\",\"type\":\"FILTER\",\"position\":{\"x\":1,\"y\":1},"
                + "\"data\":{\"config\":{\"columnName\":\"status\",\"operator\":\"CONTAINS\"}}}],"
                + "\"edges\":[{\"id\":\"edge-1\",\"source\":\"source\",\"target\":\"filter\"}]}");
        when(workflows.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(definition));

        var exception = assertThrows(ResponseStatusException.class,
                () -> service.start("owner-a", id, new WorkflowRunRequest(3L)));

        assertEquals(400, exception.getStatusCode().value());
        verify(runs, never()).saveAndFlush(any());
        verify(starter, never()).start(any());
    }

    @Test
    void acceptsSourceToFilterPreviewGraphForBackgroundExecution() throws Exception {
        UUID id = UUID.randomUUID();
        var definition = definition(id, "{\"version\":1,\"nodes\":["
                + sourceNode() + ","
                + "{\"id\":\"filter\",\"type\":\"FILTER\",\"position\":{\"x\":1,\"y\":1},"
                + "\"data\":{\"config\":{\"columnName\":\"status\",\"operator\":\"CONTAINS\","
                + "\"value\":\"late\"}}}],"
                + "\"edges\":[{\"id\":\"edge-1\",\"source\":\"source\",\"target\":\"filter\"}]}");
        when(workflows.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(definition));
        AtomicReference<com.olist.jobs.entity.WorkflowRun> savedRun = new AtomicReference<>();
        doAnswer(invocation -> {
            savedRun.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(runs).saveAndFlush(any());
        when(runs.findByIdAndOwnerId(any(), org.mockito.ArgumentMatchers.eq("owner-a")))
                .thenAnswer(invocation -> Optional.ofNullable(savedRun.get()));
        doAnswer(invocation -> null).when(transactionManager).commit(any());

        service.start("owner-a", id, new WorkflowRunRequest(3L));

        verify(starter).start(any());
    }

    @Test
    void acceptsNumericRangeFilterForBackgroundExecution() throws Exception {
        UUID id = UUID.randomUUID();
        var definition = definition(id, "{\"version\":1,\"nodes\":["
                + sourceNode() + ","
                + "{\"id\":\"filter\",\"type\":\"FILTER\",\"position\":{\"x\":1,\"y\":1},"
                + "\"data\":{\"config\":{\"columnName\":\"amount\",\"operator\":\"BETWEEN\","
                + "\"value\":\"10.5\",\"valueTo\":\"20\"}}}],"
                + "\"edges\":[{\"id\":\"edge-1\",\"source\":\"source\",\"target\":\"filter\"}]}");
        when(workflows.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(definition));
        AtomicReference<com.olist.jobs.entity.WorkflowRun> savedRun = new AtomicReference<>();
        doAnswer(invocation -> {
            savedRun.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(runs).saveAndFlush(any());
        when(runs.findByIdAndOwnerId(any(), org.mockito.ArgumentMatchers.eq("owner-a")))
                .thenAnswer(invocation -> Optional.ofNullable(savedRun.get()));
        doAnswer(invocation -> null).when(transactionManager).commit(any());

        service.start("owner-a", id, new WorkflowRunRequest(3L));

        verify(starter).start(any());
    }

    @Test
    void acceptsAFilterChainAndPersistsItForBackgroundExecution() throws Exception {
        UUID id = UUID.randomUUID();
        var definition = definition(id, "{\"version\":1,\"nodes\":["
                + sourceNode() + ","
                + "{\"id\":\"filter-1\",\"type\":\"FILTER\",\"position\":{\"x\":1,\"y\":1},"
                + "\"data\":{\"config\":{\"columnName\":\"status\",\"operator\":\"EQUALS\",\"value\":\"ready\"}}},"
                + "{\"id\":\"filter-2\",\"type\":\"FILTER\",\"position\":{\"x\":2,\"y\":2},"
                + "\"data\":{\"config\":{\"columnName\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":\"100\"}}}],"
                + "\"edges\":[{\"id\":\"edge-1\",\"source\":\"source\",\"target\":\"filter-1\"},"
                + "{\"id\":\"edge-2\",\"source\":\"filter-1\",\"target\":\"filter-2\"}]}");
        when(workflows.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(definition));
        AtomicReference<com.olist.jobs.entity.WorkflowRun> savedRun = new AtomicReference<>();
        doAnswer(invocation -> {
            savedRun.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        }).when(runs).saveAndFlush(any());
        when(runs.findByIdAndOwnerId(any(), org.mockito.ArgumentMatchers.eq("owner-a")))
                .thenAnswer(invocation -> Optional.ofNullable(savedRun.get()));
        doAnswer(invocation -> null).when(transactionManager).commit(any());

        service.start("owner-a", id, new WorkflowRunRequest(3L));

        verify(starter).start(any());
    }

    @Test
    void rejectsMalformedOrReversedNumericFilterBoundsBeforePersisting() throws Exception {
        for (String config : new String[] {
                "\"value\":\"ten\",\"valueTo\":\"20\"",
                "\"value\":\"21\",\"valueTo\":\"20\"",
                "\"value\":\"10\""
        }) {
            UUID id = UUID.randomUUID();
            var definition = definition(id, "{\"version\":1,\"nodes\":["
                    + sourceNode() + ","
                    + "{\"id\":\"filter\",\"type\":\"FILTER\",\"position\":{\"x\":1,\"y\":1},"
                    + "\"data\":{\"config\":{\"columnName\":\"amount\",\"operator\":\"BETWEEN\","
                    + config + "}}}],"
                    + "\"edges\":[{\"id\":\"edge-1\",\"source\":\"source\",\"target\":\"filter\"}]}");
            when(workflows.findByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(definition));

            var exception = assertThrows(ResponseStatusException.class,
                    () -> service.start("owner-a", id, new WorkflowRunRequest(3L)));

            assertEquals(400, exception.getStatusCode().value());
        }
        verify(runs, never()).saveAndFlush(any());
        verify(starter, never()).start(any());
    }

    private String sourceNode() {
        return "{\"id\":\"source\",\"type\":\"DATA_SOURCE\",\"position\":{\"x\":0,\"y\":0},"
                + "\"data\":{\"config\":{\"datasetVersionId\":\"550e8400-e29b-41d4-a716-446655440001\","
                + "\"tableId\":\"550e8400-e29b-41d4-a716-446655440002\"}}}";
    }

    private WorkflowDefinition definition(UUID id, String nodesJson) throws Exception {
        Instant now = Instant.now();
        String document = nodesJson.startsWith("{") ? nodesJson
                : "{\"version\":1,\"nodes\":" + nodesJson + ",\"edges\":[]}";
        return new WorkflowDefinition(id, "owner-a", "Source", 3, document, now, now);
    }
}
