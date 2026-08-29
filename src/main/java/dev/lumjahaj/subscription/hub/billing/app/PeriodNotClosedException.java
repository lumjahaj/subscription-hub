package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * The subscription exists, but its current billing period hasn't closed
 * yet - both the base charge and any usage charges land on one invoice
 * for one period, so invoicing an open period would be wrong the moment
 * it was written (usage could still accrue behind it).
 */
public class PeriodNotClosedException extends BusinessRuleViolationException {

    public PeriodNotClosedException(Instant periodEnd) {
        super("Current billing period does not close until " + periodEnd,
                "INVOICE_PERIOD_NOT_CLOSED", HttpStatus.CONFLICT);
    }
}
