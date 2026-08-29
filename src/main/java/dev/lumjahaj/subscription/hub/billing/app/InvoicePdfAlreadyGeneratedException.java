package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.common.api.BusinessRuleViolationException;
import org.springframework.http.HttpStatus;

/**
 * An invoice is immutable once issued, so its PDF is too — regenerating
 * would either produce the same bytes or, worse, silently replace a
 * document a customer has already been sent.
 *
 * Deliberately not ResourceAlreadyExistsException despite the similar
 * shape: the PDF has no identifier of its own, so this describes a
 * *state of the invoice* rather than a colliding resource, and reusing
 * the generic type would emit the mangled wire code
 * INVOICEPDF_ALREADY_EXISTS.
 */
public class InvoicePdfAlreadyGeneratedException extends BusinessRuleViolationException {

    public InvoicePdfAlreadyGeneratedException(String invoiceNumber) {
        super("PDF has already been generated for invoice " + invoiceNumber,
                "INVOICE_PDF_ALREADY_GENERATED", HttpStatus.CONFLICT);
    }
}
