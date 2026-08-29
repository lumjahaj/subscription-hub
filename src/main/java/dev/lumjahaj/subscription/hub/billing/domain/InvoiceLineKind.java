package dev.lumjahaj.subscription.hub.billing.domain;

// Only BASE and USAGE are producible today. V1's invoice_line comment
// mentions DISCOUNT | TAX, but the column is varchar(16), not a Postgres
// enum, so adding either later is a one-line change with no migration -
// no reason to declare constants nothing can produce yet.
public enum InvoiceLineKind {
    BASE,
    USAGE
}
