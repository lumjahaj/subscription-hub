package dev.lumjahaj.subscription.hub.billing.infra.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

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
    private final String region;
    private final boolean pathStyle;
    private final String accessKey;
    private final String secretKey;

    public StorageConfig(
            @Value("${billing.pdf.endpoint}") String endpoint,
            @Value("${billing.pdf.region}") String region,
            @Value("${billing.pdf.path-style}") boolean pathStyle,
            @Value("${billing.pdf.access-key}") String accessKey,
            @Value("${billing.pdf.secret-key}") String secretKey
    ) {
        this.endpoint = endpoint;
        this.region = region;
        this.pathStyle = pathStyle;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /**
     * The synchronous client specifically: invoice PDFs are small enough
     * that streaming them asynchronously buys nothing, and S3AsyncClient
     * would pull in the Netty stack that pom.xml deliberately excludes.
     *
     * Three settings here are non-obvious, and all three were hardcoded to
     * MinIO's shape until a real object store needed them:
     *
     * - endpointOverride: without it the SDK resolves the real AWS endpoint
     *   for the region and never talks to MinIO at all. Applied only when the
     *   property is set, so leaving it blank targets AWS S3 proper — the SDK
     *   cannot be given an empty override.
     * - forcePathStyle: the SDK defaults to virtual-host-style addressing
     *   (bucket.host/key), which needs a DNS entry per bucket. MinIO serves
     *   path-style (host/bucket/key); without it every request fails to
     *   resolve. R2 accepts either.
     * - region: MinIO ignores it entirely, but the SDK refuses to build a
     *   client without one. R2 requires the literal "auto", and real S3 wants
     *   the bucket's own region, so this stopped being an arbitrary
     *   placeholder the moment the vendor became a choice.
     */
    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(pathStyle)
                .region(Region.of(region));

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
