package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * Another payment for the same invoice is already PENDING or has
 * succeeded — raised when the in-flight partial unique index (V11) rejects
 * a second attempt. A distinct code from INVOICE_NOT_PAYABLE because the
 * caller should act differently: wait for the pending one to settle,
 * rather than conclude the invoice is closed.
 */
public class PaymentInProgressException extends BusinessRuleViolationException {

    public PaymentInProgressException(UUID invoiceId) {
        super("Another payment for invoice " + invoiceId + " is already pending or has succeeded",
                "PAYMENT_IN_PROGRESS", HttpStatus.CONFLICT);
    }
}
