package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceLineKind;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure — no repository, no clock, no Spring. Mirrors the split
 * SubscriptionRenewalService uses between load/save and its pure
 * applyRenewal rule, so the money arithmetic is unit-testable without a
 * database.
 */
final class InvoiceCalculator {

    private InvoiceCalculator() {
    }

    static List<InvoiceLineEntity> calculateLines(
            SubscriptionEntity subscription, List<UsageCounterEntity> counters, Map<String, MeterPrice> meterPrices) {
        List<InvoiceLineEntity> lines = new ArrayList<>();
        lines.add(baseLine(subscription.getPlan()));

        counters.stream()
                .sorted(Comparator.comparing(UsageCounterEntity::getMeterKey))
                .forEach(counter -> usageLine(counter, meterPrices.get(counter.getMeterKey())).ifPresent(lines::add));

        return lines;
    }

    static long totalCents(List<InvoiceLineEntity> lines) {
        return lines.stream().mapToLong(InvoiceLineEntity::getAmountCents).sum();
    }

    private static InvoiceLineEntity baseLine(PlanEntity plan) {
        InvoiceLineEntity line = new InvoiceLineEntity();
        line.setKind(InvoiceLineKind.BASE);
        line.setDescription(plan.getName() + " (" + plan.getCode() + ")");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitAmountCents(plan.getAmountCents());
        line.setAmountCents(plan.getAmountCents());
        return line;
    }

    /**
     * A meter with no matching price is skipped, not an error - most
     * entitlements aren't priced meters at all (see MeterPriceResolver),
     * and one unpriced meter must not blow up a tenant's entire invoice
     * run. A meter fully covered by its included quantity produces no
     * line either: a zero-amount line is a usage-report concern, not a
     * charge, and an invoice lists charges.
     */
    private static Optional<InvoiceLineEntity> usageLine(UsageCounterEntity counter, MeterPrice price) {
        if (price == null) {
            return Optional.empty();
        }
        BigDecimal billable = counter.getAmount().subtract(price.includedQuantity()).max(BigDecimal.ZERO);
        if (billable.signum() == 0) {
            return Optional.empty();
        }

        InvoiceLineEntity line = new InvoiceLineEntity();
        line.setKind(InvoiceLineKind.USAGE);
        line.setDescription(counter.getMeterKey() + " (" + billable.toPlainString() + " billable)");
        line.setQuantity(billable);
        line.setUnitAmountCents(price.unitAmountCents());
        line.setAmountCents(lineAmountCents(billable, price.unitAmountCents()));
        return Optional.of(line);
    }

    /**
     * The one place the BigDecimal quantity and integer-cents money type
     * systems meet.
     *
     * HALF_UP, not HALF_EVEN: half-up is what a customer gets checking the
     * arithmetic by hand, and an invoice line is a number a human
     * re-derives. Banker's rounding exists to avoid cumulative bias across
     * very many roundings; here there is at most one per line. Quantities
     * and prices are both non-negative, so on a tie this rounds in the
     * vendor's favour by up to half a cent.
     *
     * Rounds the product, not the quantity: usage_counter.amount is
     * numeric(20,6), so rounding the quantity first would lose sub-unit
     * usage entirely (0.4 calls at 1000 cents/call is 400 cents, not 0).
     *
     * longValueExact(), not longValue(): an absurd quantity throws rather
     * than silently wrapping into a negative charge. Loud beats wrong,
     * for money. Exactly one rounding happens, at the line - totalCents
     * sums already-rounded longs, never re-rounds a BigDecimal total, so
     * invoice.total_cents == sum(invoice_line.amount_cents) by
     * construction.
     */
    static long lineAmountCents(BigDecimal quantity, long unitAmountCents) {
        return quantity
                .multiply(BigDecimal.valueOf(unitAmountCents))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
