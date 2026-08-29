package dev.lumjahaj.subscription.hub.billing.api;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.api.mapper.InvoiceMapper;
import dev.lumjahaj.subscription.hub.billing.app.InvoiceService;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

// No request body: the period, plan price, customer and currency are all
// derived from the subscription itself, not accepted from the caller -
// the same reason UsageRecordRequest never takes a period. A client that
// could name a period could bill whatever one it liked.
@RestController
@RequestMapping("/api/subscriptions/{subscriptionId}/invoices")
public class SubscriptionInvoiceController {

    private final InvoiceService invoiceService;

    public SubscriptionInvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> generate(@PathVariable UUID subscriptionId) {
        InvoiceEntity created = invoiceService.generateForCurrentPeriod(subscriptionId);
        URI location = UriComponentsBuilder.fromPath("/api/invoices/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(InvoiceMapper.toResponse(created));
    }
}
