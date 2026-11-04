package com.olist.models.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelWorkflowRequest;
import com.olist.models.entity.ModelWorkflow;
import com.olist.models.repository.ModelWorkflowRepository;
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
class ModelWorkflowServiceTest {

    @Mock
    private ModelWorkflowRepository repository;

    private ModelWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new ModelWorkflowService(repository, new ObjectMapper());
    }

    @Test
    void savesAndReturnsAnOwnerScopedWorkflowDocument() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.save(any(ModelWorkflow.class))).thenAnswer(call -> call.getArgument(0));
        var response = service.create("owner-a", new ModelWorkflowRequest(" Forecast ",
                new ObjectMapper().readTree("{\"nodes\":[{\"id\":\"source\",\"type\":\"DATA_SOURCE\"}],\"edges\":[]}"), null));

        assertEquals("Forecast", response.name());
        assertEquals(1, response.revision());
        verify(repository).save(any(ModelWorkflow.class));
    }

    @Test
    void rejectsCyclesAndExecutableFieldsBeforeSaving() throws Exception {
        var cycle = new ModelWorkflowRequest("cycle", new ObjectMapper().readTree(
                "{\"nodes\":[{\"id\":\"a\",\"type\":\"DATA_SOURCE\"},{\"id\":\"b\",\"type\":\"FILTER\"}],"
                        + "\"edges\":[{\"source\":\"a\",\"target\":\"b\"},{\"source\":\"b\",\"target\":\"a\"}]}"), null);
        var script = new ModelWorkflowRequest("script", new ObjectMapper().readTree(
                "{\"nodes\":[],\"edges\":[],\"script\":\"print(1)\"}"), null);

        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", cycle));
        assertThrows(ResponseStatusException.class, () -> service.create("owner-a", script));
        verify(repository, never()).save(any(ModelWorkflow.class));
    }

    @Test
    void rejectsStaleRevisionAndDoesNotReadAnotherOwnersWorkflow() throws Exception {
        UUID id = UUID.randomUUID();
        ModelWorkflow existing = new ModelWorkflow(id, "owner-a", "Forecast", 3,
                "{\"nodes\":[],\"edges\":[]}", Instant.now(), Instant.now());
        when(repository.lockByIdAndOwnerId(id, "owner-a")).thenReturn(Optional.of(existing));
        when(repository.findByIdAndOwnerId(id, "owner-b")).thenReturn(Optional.empty());
        var body = new ModelWorkflowRequest("Forecast", new ObjectMapper().readTree(
                "{\"nodes\":[],\"edges\":[]}"), 2L);

        assertThrows(ResponseStatusException.class, () -> service.update("owner-a", id, body));
        assertThrows(ResponseStatusException.class, () -> service.get("owner-b", id));
        verify(repository, never()).save(any(ModelWorkflow.class));
    }
}
