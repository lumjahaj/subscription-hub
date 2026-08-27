package dev.lumjahaj.subscription.hub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PlanCreateRequest(

        @NotBlank(message = "productCode is required")
        String productCode,

        @NotBlank(message = "code is required")
        @Size(max = 64, message = "code must be at most 64 characters")
        String code,

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "intervalUnit is required")
        @Pattern(regexp = "MONTH|YEAR", message = "intervalUnit must be MONTH or YEAR")
        String intervalUnit,

        // How many intervalUnits make up one billing period — e.g. MONTH/3
        // for quarterly, MONTH/6 for semi-annual, YEAR/2 for biennial.
        // Optional: entity defaults to 1 (a plain monthly/annual plan),
        // matching how currency/trialDays default when omitted.
        @Positive(message = "intervalCount must be positive")
        Integer intervalCount,

        @PositiveOrZero(message = "amountCents must not be negative")
        long amountCents,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter code")
        String currency,

        @PositiveOrZero(message = "trialDays must not be negative")
        Integer trialDays

) {
}
