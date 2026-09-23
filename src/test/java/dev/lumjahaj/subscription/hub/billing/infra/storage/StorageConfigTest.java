package dev.lumjahaj.subscription.hub.billing.infra.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which credential source the S3 client ends up with, in isolation from
 * Spring and from any object store - the same split as InvoiceCalculatorTest
 * between the pure rule and the wiring around it.
 *
 * Worth a test rather than a reading, because both branches fail silently in
 * opposite directions. An unconditionally static provider makes an EC2
 * instance profile unreachable while looking configured; an unconditionally
 * default chain makes MinIO unreachable locally while looking secure. A built
 * S3Client does not expose the provider it was given, so this asserts on the
 * decision instead.
 */
class StorageConfigTest {

    private static StorageConfig withAccessKey(String accessKey) {
        return new StorageConfig(
                "http://localhost:9000", "us-east-1", true, accessKey, "a-secret");
    }

    @Test
    void aConfiguredAccessKey_usesStaticCredentials() {
        assertThat(withAccessKey("minio-root-user").credentialsProvider())
                .isInstanceOf(StaticCredentialsProvider.class);
    }

    /**
     * The deployed case: billing.pdf.access-key defaults to empty, so simply
     * not setting STORAGE_ACCESS_KEY selects the chain. Empty and absent are
     * both covered here because a .env loader may produce either — on Windows
     * an empty value deletes the variable outright.
     */
    @Test
    void anEmptyAccessKey_fallsBackToTheDefaultCredentialChain() {
        assertThat(withAccessKey("").credentialsProvider())
                .isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void aBlankAccessKey_fallsBackToTheDefaultCredentialChain() {
        assertThat(withAccessKey("   ").credentialsProvider())
                .isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void noAccessKeyAtAll_fallsBackToTheDefaultCredentialChain() {
        assertThat(withAccessKey(null).credentialsProvider())
                .isInstanceOf(DefaultCredentialsProvider.class);
    }
}
