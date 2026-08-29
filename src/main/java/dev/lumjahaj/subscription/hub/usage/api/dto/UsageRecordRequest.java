package dev.lumjahaj.subscription.hub.usage.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// subscriptionId intentionally NOT here - the parent subscription comes
// from the URL path, same as PlanEntitlementCreateRequest and planCode.
public record UsageRecordRequest(

        @NotBlank(message = "meterKey is required")
        @Size(max = 64, message = "meterKey must be at most 64 characters")
        String meterKey,

        // Positive, not PositiveOrZero: this is an increment applied to a
        // running total, so a zero-amount record is a no-op event, not a
        // meaningful usage fact worth recording.
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be positive")
        BigDecimal amount

) {
}
