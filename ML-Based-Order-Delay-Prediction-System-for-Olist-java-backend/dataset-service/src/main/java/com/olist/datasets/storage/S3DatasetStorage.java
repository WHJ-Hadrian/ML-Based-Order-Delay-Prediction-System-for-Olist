package com.olist.datasets.storage;

import com.olist.datasets.exception.StorageException;
import java.io.InputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3DatasetStorage implements DatasetStorage {

    private final S3Client s3;
    private final String bucket;

    public S3DatasetStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        try {
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentLength(size);
            if (contentType != null && !contentType.isBlank()) {
                request.contentType(contentType);
            }
            s3.putObject(request.build(), RequestBody.fromInputStream(content, size));
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to store dataset object " + key, exception);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to read dataset object " + key, exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to delete dataset object " + key, exception);
        }
    }
}
