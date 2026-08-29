package dev.lumjahaj.subscription.hub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Object storage for invoice PDFs. MinIO is S3-compatible, so this is the
 * plain AWS SDK pointed at a different endpoint rather than a
 * MinIO-specific client — moving to real S3, R2 or Backblaze later is a
 * config change, not a rewrite.
 */
@Configuration
public class StorageConfig {

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;

    public StorageConfig(
            @Value("${billing.pdf.endpoint}") String endpoint,
            @Value("${billing.pdf.access-key}") String accessKey,
            @Value("${billing.pdf.secret-key}") String secretKey
    ) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /**
     * The synchronous client specifically: invoice PDFs are small enough
     * that streaming them asynchronously buys nothing, and S3AsyncClient
     * would pull in the Netty stack that pom.xml deliberately excludes.
     *
     * Three settings here are non-obvious and all three are required:
     *
     * - endpointOverride: without it the SDK resolves the real AWS
     *   endpoint for the region and never talks to MinIO at all.
     * - forcePathStyle: the SDK defaults to virtual-host-style addressing
     *   (bucket.host/key), which needs a DNS entry per bucket. MinIO
     *   serves path-style (host/bucket/key); without this every request
     *   fails to resolve.
     * - region: MinIO ignores it entirely, but the SDK refuses to build a
     *   client without one, so US_EAST_1 is an arbitrary placeholder
     *   rather than a deployment decision.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .build();
    }
}
