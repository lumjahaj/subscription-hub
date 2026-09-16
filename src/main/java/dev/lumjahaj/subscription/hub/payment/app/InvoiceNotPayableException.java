package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * Only an OPEN invoice can be paid. Today that means the invoice is
 * already PAID; VOID and UNCOLLECTIBLE will land here too once something
 * produces them.
 */
public class InvoiceNotPayableException extends BusinessRuleViolationException {

    public InvoiceNotPayableException(String invoiceNumber, InvoiceStatus status) {
        super("Invoice " + invoiceNumber + " is " + status + " and cannot be paid",
                "INVOICE_NOT_PAYABLE", HttpStatus.CONFLICT);
    }
}
