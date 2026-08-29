package dev.lumjahaj.subscription.hub.billing.infra.storage;

import dev.lumjahaj.subscription.hub.billing.domain.InvoicePdfStorage;
import dev.lumjahaj.subscription.hub.billing.domain.StoredPdf;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
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

    // Not final: this is a one-time latch, not configuration. Volatile so
    // a second thread sees the first thread's successful check rather than
    // issuing a redundant headBucket call.
    private volatile boolean bucketReady = false;

    public S3InvoicePdfStorage(S3Client s3, @Value("${billing.pdf.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
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
     * Creates the bucket on first write rather than at startup.
     *
     * A @PostConstruct check would mean the application cannot boot while
     * the object store is down — coupling every endpoint, including the
     * billing ones, to a dependency only the PDF feature needs. Doing it
     * lazily keeps that failure confined to the operation that actually
     * needs storage, and it means neither local dev nor Testcontainers
     * needs a separate bucket-provisioning step.
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
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException notFound) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException lostTheRace) {
                // Another thread created it between the head and the
                // create. The bucket exists either way, which is all this
                // method promises.
            }
        }
        bucketReady = true;
    }
}
