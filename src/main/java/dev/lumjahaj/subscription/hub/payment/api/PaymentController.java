package dev.lumjahaj.subscription.hub.payment.api;

import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.payment.api.mapper.PaymentMapper;
import dev.lumjahaj.subscription.hub.payment.app.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Top-level for the same reason InvoiceController is: a payment has its own
// identity, and settlement is asynchronous, so a client needs one stable
// URL to poll for the outcome.
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(PaymentMapper.toResponse(paymentService.getById(id)));
    }
}
