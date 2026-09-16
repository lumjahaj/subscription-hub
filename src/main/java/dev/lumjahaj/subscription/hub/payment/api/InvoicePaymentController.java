package dev.lumjahaj.subscription.hub.payment.api;

import dev.lumjahaj.subscription.hub.auth.api.Authorize;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentCreateRequest;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.payment.api.mapper.PaymentMapper;
import dev.lumjahaj.subscription.hub.payment.app.PaymentAttempt;
import dev.lumjahaj.subscription.hub.payment.app.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/invoices/{invoiceId}/payments")
public class InvoicePaymentController {

    private final PaymentService paymentService;

    public InvoicePaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 201 for a new payment, 200 when the Idempotency-Key replays an
     * earlier one. With a real provider the body is usually still PENDING:
     * the outcome arrives later by webhook, so clients poll
     * GET /api/payments/{id}. The fake gateway delivers its event before
     * returning, so there the body is already settled.
     *
     * A declined card is not an error response. The attempt happened and is
     * recorded, which is exactly what dunning will count.
     *
     * The Idempotency-Key is required rather than generated when missing:
     * it is the only way a client can safely retry after a timeout, and the
     * only way to resume a payment the provider never acknowledged.
     */
    @PostMapping
    @PreAuthorize(Authorize.COMMERCIAL)
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable UUID invoiceId,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key must not be blank")
            @Size(max = 128, message = "Idempotency-Key must be at most 128 characters")
            String idempotencyKey,
            @Valid @RequestBody PaymentCreateRequest request
    ) {
        PaymentAttempt attempt = paymentService.pay(invoiceId, request.paymentMethod(), idempotencyKey);
        PaymentResponse body = PaymentMapper.toResponse(attempt.payment());
        if (!attempt.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = UriComponentsBuilder.fromPath("/api/payments/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping
    public ResponseEntity<List<PaymentResponse>> list(@PathVariable UUID invoiceId) {
        List<PaymentResponse> response = paymentService.listForInvoice(invoiceId).stream()
                .map(PaymentMapper::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }
}
