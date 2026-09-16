package dev.lumjahaj.subscription.hub.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Only the payment method. The amount and currency come from the invoice:
 * a caller who could name the amount could pay an invoice with less money
 * than it is for. The Idempotency-Key travels as a header, where Stripe
 * and most payment APIs put it, since it describes the request rather than
 * the payment.
 */
public record PaymentCreateRequest(
        @NotBlank(message = "paymentMethod is required")
        @Size(max = 64, message = "paymentMethod must be at most 64 characters")
        String paymentMethod
) {
}
