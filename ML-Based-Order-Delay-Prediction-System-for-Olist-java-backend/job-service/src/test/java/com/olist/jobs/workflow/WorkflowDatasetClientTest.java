package com.olist.jobs.workflow;

import static org.assertj.core.api.Assertions.assertThat;
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

class WorkflowDatasetClientTest {

    @Test
    void scopesPreviewRequestWithWorkerCredentialAndOwner() {
        UUID versionId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WorkflowDatasetClient client = new WorkflowDatasetClient(
                builder, "http://dataset-service", "worker-secret");

        server.expect(requestTo("http://dataset-service/internal/workflow-datasets/"
                        + versionId + "/tables/" + tableId + "/preview"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Workflow-Worker-Token", "worker-secret"))
                .andExpect(header("X-Workflow-Owner-Id", "owner-a"))
                .andRespond(withSuccess("""
                        {"tableId":"%s","name":"orders","limit":20,
                         "columns":["id"],"rows":[["1"]],"tailRows":[],
                         "totalRows":4,"truncated":true}
                        """.formatted(tableId), MediaType.APPLICATION_JSON));

        WorkflowDatasetClient.TablePreview preview = client.preview(versionId, tableId, "owner-a");

        assertThat(preview.rows()).containsExactly(java.util.List.of("1"));
        assertThat(preview.totalRows()).isEqualTo(4);
        server.verify();
    }
}
