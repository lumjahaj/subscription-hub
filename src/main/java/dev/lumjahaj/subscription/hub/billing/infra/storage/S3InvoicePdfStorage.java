package dev.lumjahaj.subscription.hub.billing.infra.storage;

import dev.lumjahaj.subscription.hub.billing.domain.InvoicePdfStorage;
import dev.lumjahaj.subscription.hub.billing.domain.StoredPdf;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3InvoicePdfStorage implements InvoicePdfStorage {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final S3Client s3;
    private final String bucket;
    private final String region;
    private final boolean createIfMissing;

    // Not final: this is a one-time latch, not configuration. Volatile so
    // a second thread sees the first thread's successful check rather than
    // issuing a redundant headBucket call.
    private volatile boolean bucketReady = false;

    public S3InvoicePdfStorage(
            S3Client s3,
            @Value("${billing.pdf.bucket}") String bucket,
            @Value("${billing.pdf.region}") String region,
            @Value("${billing.pdf.create-bucket-if-missing}") boolean createIfMissing
    ) {
        this.s3 = s3;
        this.bucket = bucket;
        this.region = region;
        this.createIfMissing = createIfMissing;
    }

    @Override
    public void store(String objectKey, byte[] content) {
        ensureBucket();
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType(PDF_CONTENT_TYPE)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public StoredPdf load(String objectKey) {
        ResponseInputStream<GetObjectResponse> object = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
        return new StoredPdf(object, object.response().contentLength());
    }

    /**
     * Creates the bucket on first write rather than at startup — when the
     * deployment asks for that at all.
     *
     * A @PostConstruct check would mean the application cannot boot while
     * the object store is down — coupling every endpoint, including the
     * billing ones, to a dependency only the PDF feature needs. Doing it
     * lazily keeps that failure confined to the operation that actually
     * needs storage, and it means neither local dev nor Testcontainers
     * needs a separate bucket-provisioning step.
     *
     * Against a real object store the bucket is infrastructure instead, the
     * same way the SQS queues are: provisioned before a deploy touches it,
     * never by the application. Turning creation off is not only tidiness —
     * it removes both S3 calls from the write path, which means the deployed
     * credential needs no s3:ListBucket grant. Without that grant HeadBucket
     * answers 403 rather than 404, which is not NoSuchBucketException and
     * would surface as an opaque S3Exception on the first invoice.
     *
     * Two callers racing the first write both create the bucket; the loser
     * gets BucketAlreadyOwnedByYou, which is why the create is tolerant
     * rather than guarded by a lock. The flag only avoids a redundant
     * round trip on every subsequent write.
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        if (!createIfMissing) {
            bucketReady = true;
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException notFound) {
            try {
                s3.createBucket(createBucketRequest(bucket, region));
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException lostTheRace) {
                // Another thread created it between the head and the
                // create. The bucket exists either way, which is all this
                // method promises.
            }
        }
        bucketReady = true;
    }

    /**
     * A create request carrying the region as a location constraint,
     * except in us-east-1.
     *
     * S3 treats us-east-1 as the default and rejects a request that names it
     * explicitly; every other region rejects a request that omits it, with
     * IllegalLocationConstraintException. This used to send no constraint at
     * all, so creation could only ever have succeeded in us-east-1 — harmless
     * while the only target was MinIO, which ignores the field entirely, and
     * wrong the moment a real bucket in eu-central-1 was the target.
     *
     * Package-private so it can be unit-tested, following
     * StripePaymentGateway.outcomeOf: MinIO accepts either form, so an
     * integration test against it cannot tell the two apart.
     */
    static CreateBucketRequest createBucketRequest(String bucket, String region) {
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(bucket);
        if (!Region.US_EAST_1.id().equals(region)) {
            request.createBucketConfiguration(
                    CreateBucketConfiguration.builder().locationConstraint(region).build());
        }
        return request.build();
    }
}
