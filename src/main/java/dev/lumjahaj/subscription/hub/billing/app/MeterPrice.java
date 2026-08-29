package dev.lumjahaj.subscription.hub.billing.app;

import java.math.BigDecimal;

record MeterPrice(BigDecimal includedQuantity, long unitAmountCents) {
}
