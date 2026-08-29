package dev.lumjahaj.subscription.hub.billing.api;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.api.mapper.InvoiceMapper;
import dev.lumjahaj.subscription.hub.billing.app.InvoiceService;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.common.api.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// Top-level, not nested under /api/subscriptions - an invoice is a
// financial document with its own identity and canonical URL, the way
// SubscriptionController is top-level even though every subscription
// belongs to a customer.
@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
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
}
