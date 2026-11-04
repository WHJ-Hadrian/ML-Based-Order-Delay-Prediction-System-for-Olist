package com.olist.models.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TrainingDatasetClientTest {

    @Test
    void addsBearerPrefixExactlyOnceToRawCallerToken() {
        UUID versionId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TrainingDatasetClient client = new TrainingDatasetClient(builder, "http://dataset-service");

        server.expect(requestTo("http://dataset-service/api/v1/dataset-versions/" + versionId + "/catalog"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer caller-token"))
                .andRespond(withSuccess(
                        "{\"datasetId\":\"" + UUID.randomUUID()
                                + "\",\"datasetVersionId\":\"" + versionId
                                + "\",\"versionNo\":1,\"status\":\"READY\",\"tables\":[]}",
                        MediaType.APPLICATION_JSON));

        client.catalog(versionId, "caller-token");

        server.verify();
    }
}
