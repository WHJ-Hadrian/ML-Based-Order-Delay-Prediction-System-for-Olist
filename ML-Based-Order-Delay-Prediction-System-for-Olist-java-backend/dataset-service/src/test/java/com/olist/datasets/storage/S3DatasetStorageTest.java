package com.olist.datasets.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3DatasetStorageTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3DatasetStorage storage = new S3DatasetStorage(s3, "olist-artifacts");

    @Test
    void putsObjectWithBucketKeySizeAndContentType() {
        byte[] content = "order_id\n1\n".getBytes(StandardCharsets.UTF_8);

        storage.put("datasets/1/raw/orders.csv", new ByteArrayInputStream(content), content.length, "text/csv");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("olist-artifacts");
        assertThat(request.getValue().key()).isEqualTo("datasets/1/raw/orders.csv");
        assertThat(request.getValue().contentLength()).isEqualTo((long) content.length);
        assertThat(request.getValue().contentType()).isEqualTo("text/csv");
    }
}
