package dev.lumjahaj.subscription.hub.subscription.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The plan the subscription moves onto at its next renewal. Addressed by code,
 * consistent with SubscriptionCreateRequest and with how plans are addressed
 * everywhere else.
 *
 * No "when" field: a change always takes effect at the next period boundary, so
 * there is nothing to choose. Immediate, prorated changes would need a credit
 * and a charge on the next invoice and are deliberately not built.
 */
public record SubscriptionPlanChangeRequest(

        @NotBlank(message = "planCode is required")
        String planCode

) {
}
