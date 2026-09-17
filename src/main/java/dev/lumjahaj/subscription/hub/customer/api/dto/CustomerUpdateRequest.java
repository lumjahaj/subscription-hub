package dev.lumjahaj.subscription.hub.customer.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * A full replacement of a customer's editable fields, for PUT - so an omitted
 * externalId clears it, the same as sending null.
 *
 * Deliberately the same fields and rules as CustomerCreateRequest rather than
 * a PATCH document: a record cannot tell an absent field from an explicit
 * null, and telling them apart would need JsonNullable or JSON Merge Patch for
 * a resource with three fields.
 *
 * The payment method is not here. It has its own sub-resource, because it is
 * a provider credential with its own audit events, not profile data.
 */
public record CustomerUpdateRequest(

        String externalId,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        String email,

        @NotBlank(message = "name is required")
        String name

) {
}
