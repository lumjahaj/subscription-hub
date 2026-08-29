package dev.lumjahaj.subscription.hub.billing.infra.pdf;

import java.util.List;

/**
 * What the Thymeleaf template renders. Everything here is already a
 * String: money has been converted from integer minor units to a decimal
 * display form, and timestamps have been formatted, so the template does
 * no arithmetic and no locale work of its own.
 *
 * That split is deliberate rather than incidental — the money rule
 * (CLAUDE.md §5) is that amounts stay integer cents everywhere, and this
 * view model is exactly where that rule stops applying, once and in one
 * place, at the display boundary.
 */
record InvoicePdfView(
        String number,
        String status,
        String currency,
        String issuedAt,
        String dueAt,
        String periodStart,
        String periodEnd,
        String customerName,
        String customerEmail,
        String total,
        List<Line> lines
) {

    record Line(
            String kind,
            String description,
            String quantity,
            String unitAmount,
            String amount
    ) {
    }
}
