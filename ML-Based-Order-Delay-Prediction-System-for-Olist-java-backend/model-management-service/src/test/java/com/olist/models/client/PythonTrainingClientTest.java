package com.olist.models.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class PythonTrainingClientTest {

    @Test
    void requestsOwnerScopedManifestWithInternalBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PythonTrainingClient client = new PythonTrainingClient(
                builder, "http://model-serving:8090", "service-token");

        server.expect(requestTo("http://model-serving:8090/internal/v1/model-artifacts/artifact-1/manifest?ownerId=user-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer service-token"))
                .andRespond(withSuccess("{\"schemaVersion\":2}", MediaType.APPLICATION_JSON));

        Map<String, Object> manifest = client.manifest("user-1", "artifact-1");

        assertEquals(2, manifest.get("schemaVersion"));
        server.verify();
    }

    @Test
    void streamsOwnerScopedZipBundleToCallerOutputStream() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PythonTrainingClient client = new PythonTrainingClient(
                builder, "http://model-serving:8090", "service-token");
        byte[] zip = "PK-test-bundle".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        server.expect(requestTo("http://model-serving:8090/internal/v1/model-artifacts/artifact-1/export.zip?ownerId=user-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer service-token"))
                .andRespond(withSuccess(zip, MediaType.parseMediaType("application/zip")));

        client.streamArtifact("user-1", "artifact-1", output);

        assertArrayEquals(zip, output.toByteArray());
        server.verify();
    }

    @Test
    void mapsUpstreamNotFoundDuringStreamingToNotFound() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PythonTrainingClient client = new PythonTrainingClient(
                builder, "http://model-serving:8090", "service-token");

        server.expect(requestTo("http://model-serving:8090/internal/v1/model-artifacts/artifact-1/export.zip?ownerId=user-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"detail\":\"not found\"}"));

        var error = assertThrows(ResponseStatusException.class,
                () -> client.streamArtifact("user-1", "artifact-1", new ByteArrayOutputStream()));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        server.verify();
    }
}
