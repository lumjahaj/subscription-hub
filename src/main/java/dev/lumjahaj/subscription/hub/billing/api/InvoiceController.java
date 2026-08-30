package dev.lumjahaj.subscription.hub.billing.api;

import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.api.mapper.InvoiceMapper;
import dev.lumjahaj.subscription.hub.billing.app.InvoicePdfDownload;
import dev.lumjahaj.subscription.hub.billing.app.InvoicePdfService;
import dev.lumjahaj.subscription.hub.billing.app.InvoiceService;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

// Top-level, not nested under /api/subscriptions - an invoice is a
// financial document with its own identity and canonical URL, the way
// SubscriptionController is top-level even though every subscription
// belongs to a customer.
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;

    public InvoiceController(InvoiceService invoiceService, InvoicePdfService invoicePdfService) {
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponse> getById(@PathVariable UUID id) {
        InvoiceEntity invoice = invoiceService.getById(id);
        return ResponseEntity.ok(InvoiceMapper.toResponse(invoice));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<InvoiceResponse>> list(
            @RequestParam(required = false) UUID subscriptionId,
            Pageable pageable
    ) {
        Page<InvoiceEntity> page = subscriptionId != null
                ? invoiceService.listBySubscription(subscriptionId, pageable)
                : invoiceService.list(pageable);
        return ResponseEntity.ok(PagedResponse.from(page, InvoiceMapper::toResponse));
    }

    // No request body: everything the PDF needs is already on the invoice.
    // 201 rather than 200 because this creates a document that did not
    // exist before, and Location points at where it can now be fetched.
    @PostMapping("/{id}/pdf")
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<InvoiceResponse> generatePdf(@PathVariable UUID id) {
        InvoiceEntity invoice = invoicePdfService.generatePdf(id);
        URI location = UriComponentsBuilder.fromPath("/api/invoices/{id}/pdf")
                .buildAndExpand(invoice.getId())
                .toUri();
        return ResponseEntity.created(location).body(InvoiceMapper.toResponse(invoice));
    }

    /**
     * Streams the stored PDF back through the API rather than redirecting
     * to a presigned object-store URL. That keeps every download inside
     * the tenant-scoped request path — TenantResolverFilter and the
     * ownership check in InvoicePdfService both still apply — and means
     * the object store is never reachable from outside the application.
     *
     * InputStreamResource (not ByteArrayResource) so the bytes go from
     * the object store to the socket without being buffered in full;
     * Spring closes the stream once the response is written.
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<Resource> downloadPdf(@PathVariable UUID id) {
        InvoicePdfDownload download = invoicePdfService.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(download.pdf().contentLength())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename()).build().toString())
                .body(new InputStreamResource(download.pdf().content()));
    }
}
