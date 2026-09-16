package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * The provider could not be reached. The payment stays PENDING because the
 * outcome is genuinely unknown — a timeout can happen after the provider
 * charged. Retrying with the same Idempotency-Key resumes it, and the
 * provider's own idempotency makes that safe.
 */
public class PaymentProviderUnavailableException extends BusinessRuleViolationException {

    public PaymentProviderUnavailableException() {
        super("Payment provider is unavailable; retry with the same Idempotency-Key",
                "PAYMENT_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
