package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * The invoice exists but has no PDF yet — generation is decoupled from
 * invoice creation, so there is a real window where this is the normal
 * state rather than an error in the request.
 *
 * A 404 like ResourceNotFoundException, but a distinct type because the
 * difference carries real information the caller acts on: "no such
 * invoice" means the id is wrong, while this means POST the PDF endpoint
 * and try again.
 */
public class InvoicePdfNotGeneratedException extends BusinessRuleViolationException {

    public InvoicePdfNotGeneratedException(String invoiceNumber) {
        super("No PDF has been generated for invoice " + invoiceNumber,
                "INVOICE_PDF_NOT_GENERATED", HttpStatus.NOT_FOUND);
    }
}
