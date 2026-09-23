package dev.lumjahaj.subscription.hub.billing.infra.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket-creation request, in isolation from any object store.
 *
 * Worth a unit test specifically because no integration test can cover it:
 * MinIO accepts a create request with or without a location constraint and
 * ignores the field either way, so InvoicePdfIntegrationTest passes against
 * both the correct and the incorrect request. Real S3 is the only thing that
 * tells them apart, and it is exactly what CI must never call.
 */
class S3InvoicePdfStorageTest {

    /**
     * S3 rejects a request that names us-east-1 explicitly, because it is the
     * default. Sending no constraint is correct only here.
     */
    @Test
    void inUsEast1_sendsNoLocationConstraint() {
        CreateBucketRequest request =
                S3InvoicePdfStorage.createBucketRequest("invoices", "us-east-1");

        assertThat(request.bucket()).isEqualTo("invoices");
        assertThat(request.createBucketConfiguration()).isNull();
    }

    /**
     * Every other region rejects a request that omits it, with
     * IllegalLocationConstraintException. Omitting it is what the code used
     * to do unconditionally, so creation could only ever have worked in
     * us-east-1.
     */
    @Test
    void inAnyOtherRegion_sendsTheRegionAsTheLocationConstraint() {
        CreateBucketRequest request =
                S3InvoicePdfStorage.createBucketRequest("invoices", "eu-central-1");

        assertThat(request.bucket()).isEqualTo("invoices");
        assertThat(request.createBucketConfiguration()).isNotNull();
        assertThat(request.createBucketConfiguration().locationConstraintAsString())
                .isEqualTo("eu-central-1");
    }
}
