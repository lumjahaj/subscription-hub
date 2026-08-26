package dev.lumjahaj.subscription.hub.testsupport;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Shared Bean Validation Validator for mapper tests that also assert on
 * request DTO validation (@NotBlank, @Pattern, etc.). Extracted once the
 * same static Validator setup appeared identically in Product/Plan/
 * PlanEntitlement mapper tests, with Customer and Subscription about to
 * repeat it a fourth and fifth time.
 */
public abstract class MapperValidationSupport {

    protected static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }
}