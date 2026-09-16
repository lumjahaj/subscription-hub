package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * An Idempotency-Key promises "this is the same request again". Replaying
 * the earlier result for a request that differs (another invoice, another
 * payment method) would tell the caller something succeeded that they
 * never actually asked for, so it is refused instead.
 */
public class IdempotencyKeyReusedException extends BusinessRuleViolationException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used for a different payment request",
                "IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT);
    }
}
