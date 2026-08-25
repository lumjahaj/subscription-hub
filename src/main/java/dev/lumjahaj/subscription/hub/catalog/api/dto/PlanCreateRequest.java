package dev.lumjahaj.subscription.hub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PlanCreateRequest(

        @NotBlank(message = "code is required")
        @Size(max = 64, message = "code must be at most 64 characters")
        String code,

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "interval is required")
        @Pattern(regexp = "MONTH|YEAR", message = "interval must be MONTH or YEAR")
        String interval,

        @PositiveOrZero(message = "amountCents must not be negative")
        long amountCents,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter code")
        String currency,

        @PositiveOrZero(message = "trialDays must not be negative")
        Integer trialDays

) {
}
