package com.olist.datasets.config;

import com.olist.datasets.storage.DatasetStorage;
import com.olist.datasets.storage.LocalFileSystemDatasetStorage;
import com.olist.datasets.storage.S3DatasetStorage;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableConfigurationProperties({StorageProperties.class, TableSearchProperties.class})
public class StorageConfiguration {

    @Bean
    @ConditionalOnProperty(name = "olist.storage.type", havingValue = "local", matchIfMissing = true)
    DatasetStorage localFileSystemDatasetStorage(StorageProperties properties) {
        return new LocalFileSystemDatasetStorage(Path.of(properties.getLocalRoot()));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "olist.storage.type", havingValue = "s3")
    S3Client s3Client(StorageProperties properties) {
        StorageProperties.S3 s3 = properties.getS3();
        AwsCredentialsProvider credentials = s3.getAccessKey() != null && !s3.getAccessKey().isBlank()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()))
                : DefaultCredentialsProvider.create();
        return S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "olist.storage.type", havingValue = "s3")
    DatasetStorage s3DatasetStorage(S3Client s3Client, StorageProperties properties) {
        return new S3DatasetStorage(s3Client, properties.getS3().getBucket());
    }
}
