package dev.lumjahaj.subscription.hub.billing.domain;

import java.util.UUID;

/**
 * Notified when an invoice is generated, so other modules can react without
 * billing knowing they exist.
 *
 * This is how notification hears about a new invoice while the dependency
 * still points notification → billing (it already reads invoices and PDFs):
 * billing defines the port, notification implements it. Reversing that
 * would give billing opinions about email, and ArchUnit would reject the
 * cycle - the same shape as {@code PaymentOutcomeListener} in payment.
 *
 * Implementations run inside {@code generateForCurrentPeriod}'s
 * transaction, so whatever they write commits — or rolls back — with the
 * invoice itself. They must not make a remote call.
 */
public interface InvoiceIssuedListener {

    void onInvoiceIssued(UUID invoiceId);
}
