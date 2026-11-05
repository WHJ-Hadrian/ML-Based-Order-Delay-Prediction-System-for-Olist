package com.olist.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.entity.WorkflowRunNode;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.url=jdbc:h2:mem:workflow-definition-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/olist",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/olist/protocol/openid-connect/certs"
})
class WorkflowPersistenceTest {
    @Autowired private WorkflowDefinitionRepository repository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowRunNodeRepository nodeRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void migratesAndReopensOwnerScopedDefinitions() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        repository.saveAndFlush(new WorkflowDefinition(id, "owner-a", "Customer prep", 3,
                "{\"version\":1,\"nodes\":[],\"edges\":[]}", now, now));

        var reopened = repository.findByIdAndOwnerId(id, "owner-a").orElseThrow();
        assertEquals(3, reopened.getRevision());
        assertTrue(objectMapper.readTree(reopened.getDocumentJson()).path("edges").isArray());
        assertTrue(repository.findByIdAndOwnerId(id, "owner-b").isEmpty());
    }

    @Test
    void persistsRunSnapshotAndNodeResultAcrossRepositoryReads() {
        UUID workflowId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        repository.saveAndFlush(new WorkflowDefinition(workflowId, "owner-a", "Source", 4,
                "{\"nodes\":[],\"edges\":[]}", now, now));
        runRepository.saveAndFlush(new WorkflowRun(runId, workflowId, "owner-a", "Source", 4,
                "{\"nodes\":[{\"id\":\"source-1\"}],\"edges\":[]}", null, now));
        WorkflowRunNode node = new WorkflowRunNode(UUID.randomUUID(), runId, "source-1", "DATA_SOURCE", now);
        node.start(now);
        node.succeed("{\"rowCount\":12}", now);
        nodeRepository.saveAndFlush(node);

        var reopened = runRepository.findByIdAndOwnerId(runId, "owner-a").orElseThrow();

        assertEquals("QUEUED", reopened.getStatus());
        assertEquals(4, reopened.getWorkflowRevision());
        assertTrue(reopened.getSnapshotJson().contains("source-1"));
        assertTrue(runRepository.findByIdAndOwnerId(runId, "owner-b").isEmpty());
        assertEquals("{\"rowCount\":12}", nodeRepository.findByRunIdOrderByCreatedAtAsc(runId)
                .get(0).getResultJson());
    }

    @Test
    void recentRunQueryIsScopedToTheAuthenticatedOwner() {
        Instant now = Instant.now();
        UUID workflowA = UUID.randomUUID();
        UUID workflowB = UUID.randomUUID();
        runRepository.saveAndFlush(new WorkflowRun(UUID.randomUUID(), workflowA, "owner-a", "A", 1,
                "{\"nodes\":[],\"edges\":[]}", null, now));
        runRepository.saveAndFlush(new WorkflowRun(UUID.randomUUID(), workflowB, "owner-b", "B", 1,
                "{\"nodes\":[],\"edges\":[]}", null, now));

        var ownerARuns = runRepository.findTop50ByOwnerIdOrderByCreatedAtDesc("owner-a");

        assertEquals(1, ownerARuns.size());
        assertEquals("A", ownerARuns.get(0).getWorkflowName());
        assertTrue(runRepository.findTop50ByOwnerIdOrderByCreatedAtDesc("owner-c").isEmpty());
    }
}
