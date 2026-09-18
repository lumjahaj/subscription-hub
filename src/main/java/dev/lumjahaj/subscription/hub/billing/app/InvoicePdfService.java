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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class InvoicePdfService {

    private final InvoiceRepository invoices;
    private final InvoicePdfRenderer renderer;
    private final InvoicePdfStorage storage;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    public InvoicePdfService(
            InvoiceRepository invoices,
            InvoicePdfRenderer renderer,
            InvoicePdfStorage storage,
            TransactionTemplate transaction
    ) {
        this.invoices = invoices;
        this.renderer = renderer;
        this.storage = storage;
        this.transaction = transaction;
        // A separate instance rather than mutating the shared bean, which is
        // used elsewhere: TransactionTemplate is a shared singleton, and
        // setting readOnly on it would leak into every other user.
        this.readOnlyTransaction = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Renders the invoice, stores the PDF, and records the object key.
     *
     * Three steps around one remote call, the same split PaymentService and
     * NotificationRelayService use: load and render in a short transaction,
     * upload with none open, record in a second.
     *
     * It used to be one @Transactional method whose javadoc argued that
     * holding a transaction across the upload was acceptable here, because a
     * storage failure would roll back a transaction that had written nothing.
     * That reasoning was about rollback, and it missed a lost update. The
     * upload is slow, so a payment could settle between the load and the
     * save, and saving the loaded entity then wrote its stale OPEN status and
     * null paid_at back over the settlement - leaving a paid invoice looking
     * unpaid, to be collected a second time by dunning. Found by running the
     * app, not by a test: the relay is idle in the suite, so nothing ever
     * generated a PDF concurrently with a payment.
     *
     * Rendering stays inside the first transaction because it needs the lazy
     * customer and lines associations (open-in-view is off) and is local CPU
     * work, not a remote call - the same reason NotificationService renders
     * email bodies inside its caller's transaction.
     */
    public InvoiceEntity generatePdf(UUID invoiceId) {
        String tenantId = TenantContext.getTenantId();

        RenderedInvoice rendered = readOnlyTransaction.execute(status -> {
            InvoiceEntity invoice = findOwned(tenantId, invoiceId);
            if (invoice.getPdfObjectKey() != null) {
                throw new InvoicePdfAlreadyGeneratedException(invoice.getNumber());
            }
            return new RenderedInvoice(invoice.getNumber(), renderer.render(invoice));
        });

        String objectKey = objectKeyFor(tenantId, rendered.number());
        storage.store(objectKey, rendered.pdf());

        return transaction.execute(status -> {
            // Writes pdf_object_key alone, so nothing that committed during
            // the upload can be clobbered by this call.
            if (!invoices.attachPdfObjectKeyIfAbsent(tenantId, invoiceId, objectKey)) {
                // Someone generated it while we were uploading. Their key is
                // this same deterministic one and the bytes are identical, so
                // the stored object is fine; only one caller may report
                // success. An invoice deleted meanwhile falls through to 404.
                throw new InvoicePdfAlreadyGeneratedException(findOwned(tenantId, invoiceId).getNumber());
            }
            return findOwned(tenantId, invoiceId);
        });
    }

    /** The invoice number and its rendered bytes, carried out of the read transaction. */
    private record RenderedInvoice(String number, byte[] pdf) {
    }

    private InvoiceEntity findOwned(String tenantId, UUID invoiceId) {
        return invoices.findByTenantIdAndId(tenantId, invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId.toString()));
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
