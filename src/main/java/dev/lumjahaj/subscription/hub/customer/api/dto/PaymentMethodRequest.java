package dev.lumjahaj.subscription.hub.customer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A provider token, not card details. The card is exchanged for this token
 * by the provider's own client SDK, so it never reaches this application —
 * which is the whole reason a billing backend can stay out of PCI scope.
 */
public record PaymentMethodRequest(
        @NotBlank(message = "paymentMethod is required")
        @Size(max = 64, message = "paymentMethod must be at most 64 characters")
        String paymentMethod
) {
}
