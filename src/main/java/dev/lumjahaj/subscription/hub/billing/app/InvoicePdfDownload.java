package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.billing.domain.StoredPdf;

/**
 * A PDF ready to be written to an HTTP response: the stored bytes plus
 * the filename to advertise in Content-Disposition.
 *
 * The filename is derived from the invoice number rather than the object
 * key, so what a customer sees on disk ("INV-000001.pdf") never leaks the
 * tenant-prefixed storage layout.
 */
public record InvoicePdfDownload(String filename, StoredPdf pdf) {
}
