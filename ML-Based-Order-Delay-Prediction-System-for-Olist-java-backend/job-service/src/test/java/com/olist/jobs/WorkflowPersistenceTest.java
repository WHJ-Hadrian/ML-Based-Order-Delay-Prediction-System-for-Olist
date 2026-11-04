package com.olist.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
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
}
