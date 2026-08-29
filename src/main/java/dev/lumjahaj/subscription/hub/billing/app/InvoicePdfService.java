package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoicePdfStorage;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceRepository;
import dev.lumjahaj.subscription.hub.billing.domain.StoredPdf;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.billing.infra.pdf.InvoicePdfRenderer;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InvoicePdfService {

    private final InvoiceRepository invoices;
    private final InvoicePdfRenderer renderer;
    private final InvoicePdfStorage storage;

    public InvoicePdfService(
            InvoiceRepository invoices,
            InvoicePdfRenderer renderer,
            InvoicePdfStorage storage
    ) {
        this.invoices = invoices;
        this.renderer = renderer;
        this.storage = storage;
    }

    /**
     * Renders the invoice and stores the PDF, recording the object key on
     * the invoice.
     *
     * Reloads inside the transaction, like InvoiceService and
     * SubscriptionRenewalService, so the lazy customer/lines associations
     * are still fetchable when BillingCycleJob calls this outside a
     * request.
     *
     * The render and upload deliberately happen *before* the entity is
     * mutated. This method holds a transaction across a remote call, which
     * is exactly what generateForCurrentPeriod avoids — the difference is
     * that there the upload was an unrelated side effect on the billing
     * path, whereas here storing the bytes *is* the operation, and there
     * is nothing worth committing if it fails. Ordering it this way means
     * a storage failure rolls back a transaction that has written nothing.
     */
    @Transactional
    public InvoiceEntity generatePdf(UUID invoiceId) {
        String tenantId = TenantContext.getTenantId();
        InvoiceEntity invoice = invoices.findByTenantIdAndId(tenantId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId.toString()));

        if (invoice.getPdfObjectKey() != null) {
            throw new InvoicePdfAlreadyGeneratedException(invoice.getNumber());
        }

        byte[] pdf = renderer.render(invoice);
        String objectKey = objectKeyFor(tenantId, invoice.getNumber());
        storage.store(objectKey, pdf);

        invoice.setPdfObjectKey(objectKey);
        return invoices.save(invoice);
    }

    /**
     * Read-only: opens the stored object for streaming rather than
     * buffering it, so the caller must close the returned stream (the
     * controller hands it to Spring, which does).
     */
    @Transactional(readOnly = true)
    public InvoicePdfDownload download(UUID invoiceId) {
        String tenantId = TenantContext.getTenantId();
        InvoiceEntity invoice = invoices.findByTenantIdAndId(tenantId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId.toString()));

        if (invoice.getPdfObjectKey() == null) {
            throw new InvoicePdfNotGeneratedException(invoice.getNumber());
        }

        StoredPdf stored = storage.load(invoice.getPdfObjectKey());
        return new InvoicePdfDownload(invoice.getNumber() + ".pdf", stored);
    }

    /**
     * Tenant-prefixed so one bucket holds every tenant's invoices without
     * their keys ever colliding — invoice numbers restart per tenant, so
     * "INV-000001.pdf" alone would be ambiguous. It also makes the bucket
     * browsable per tenant in the MinIO console.
     *
     * This is a storage-layout convenience, not a security boundary:
     * isolation is enforced by loading the invoice through
     * findByTenantIdAndId first, so a key is only ever read after the
     * requesting tenant has been shown to own the invoice.
     */
    private static String objectKeyFor(String tenantId, String invoiceNumber) {
        return tenantId + "/" + invoiceNumber + ".pdf";
    }
}
