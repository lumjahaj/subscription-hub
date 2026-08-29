package dev.lumjahaj.subscription.hub.billing.domain;

/**
 * Domain port for invoice PDF bytes — the same seam idea as the repository
 * trio, one layer out: the app layer depends on this contract, and
 * S3InvoicePdfStorage in infra is the only thing that knows the bytes live
 * in an S3-compatible bucket. Swapping in a filesystem or a different
 * object store later means one new adapter and no change above.
 */
public interface InvoicePdfStorage {

    void store(String objectKey, byte[] content);

    /**
     * Opens the stored object for reading. The caller owns the returned
     * stream and must close it — the controller hands it to Spring as an
     * InputStreamResource, which closes it once the response is written,
     * so the bytes are never buffered in memory a second time.
     */
    StoredPdf load(String objectKey);
}
