package dev.lumjahaj.subscription.hub.billing.app;

import dev.lumjahaj.subscription.hub.billing.domain.InvoiceLineKind;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;
import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function coverage of the money arithmetic, in isolation from
 * Spring and the database - mirrors BillingPeriodsTest and
 * SubscriptionRenewalServiceTest's split between load/save and the pure
 * rule underneath.
 */
class InvoiceCalculatorTest {

    private static SubscriptionEntity subscriptionWithPlan(long amountCents) {
        PlanEntity plan = new PlanEntity();
        plan.setCode("smoke-plan");
        plan.setName("Smoke Plan");
        plan.setIntervalUnit(IntervalUnit.MONTH);
        plan.setIntervalCount(1);
        plan.setAmountCents(amountCents);

        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.setPlan(plan);
        return subscription;
    }

    private static UsageCounterEntity counter(String meterKey, String amount) {
        UsageCounterEntity counter = new UsageCounterEntity();
        counter.setMeterKey(meterKey);
        counter.setAmount(new BigDecimal(amount));
        return counter;
    }

    @Test
    void calculateLines_withNoUsage_producesOnlyTheBaseLine() {
        SubscriptionEntity subscription = subscriptionWithPlan(2999);

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(subscription, List.of(), Map.of());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getKind()).isEqualTo(InvoiceLineKind.BASE);
        assertThat(lines.get(0).getAmountCents()).isEqualTo(2999);
    }

    @Test
    void calculateLines_withUsageAboveTheIncludedQuantity_billsOnlyTheOverage() {
        SubscriptionEntity subscription = subscriptionWithPlan(2999);
        UsageCounterEntity counter = counter("api.calls", "150");
        MeterPrice price = new MeterPrice(new BigDecimal("100"), 5);

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(
                subscription, List.of(counter), Map.of("api.calls", price));

        assertThat(lines).hasSize(2);
        InvoiceLineEntity usageLine = lines.get(1);
        assertThat(usageLine.getKind()).isEqualTo(InvoiceLineKind.USAGE);
        assertThat(usageLine.getQuantity()).isEqualByComparingTo("50");
        assertThat(usageLine.getAmountCents()).isEqualTo(250);
    }

    @Test
    void calculateLines_withUsageWithinTheIncludedQuantity_producesNoUsageLine() {
        SubscriptionEntity subscription = subscriptionWithPlan(2999);
        UsageCounterEntity counter = counter("api.calls", "80");
        MeterPrice price = new MeterPrice(new BigDecimal("100"), 5);

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(
                subscription, List.of(counter), Map.of("api.calls", price));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getKind()).isEqualTo(InvoiceLineKind.BASE);
    }

    @Test
    void calculateLines_withAMeterThatHasNoPrice_skipsThatMeter() {
        SubscriptionEntity subscription = subscriptionWithPlan(2999);
        UsageCounterEntity counter = counter("unpriced.meter", "1000");

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(subscription, List.of(counter), Map.of());

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getKind()).isEqualTo(InvoiceLineKind.BASE);
    }

    @Test
    void calculateLines_ordersTheBaseLineBeforeUsageLinesAndUsageLinesByMeterKey() {
        SubscriptionEntity subscription = subscriptionWithPlan(2999);
        UsageCounterEntity zCounter = counter("z.meter", "10");
        UsageCounterEntity aCounter = counter("a.meter", "10");
        Map<String, MeterPrice> prices = Map.of(
                "z.meter", new MeterPrice(BigDecimal.ZERO, 1),
                "a.meter", new MeterPrice(BigDecimal.ZERO, 1));

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(
                subscription, List.of(zCounter, aCounter), prices);

        assertThat(lines).extracting(InvoiceLineEntity::getKind)
                .containsExactly(InvoiceLineKind.BASE, InvoiceLineKind.USAGE, InvoiceLineKind.USAGE);
        assertThat(lines.get(1).getDescription()).startsWith("a.meter");
        assertThat(lines.get(2).getDescription()).startsWith("z.meter");
    }

    @Test
    void totalCents_equalsTheSumOfTheLineAmounts() {
        SubscriptionEntity subscription = subscriptionWithPlan(2999);
        UsageCounterEntity counter = counter("api.calls", "150");
        MeterPrice price = new MeterPrice(new BigDecimal("100"), 5);

        List<InvoiceLineEntity> lines = InvoiceCalculator.calculateLines(
                subscription, List.of(counter), Map.of("api.calls", price));

        assertThat(InvoiceCalculator.totalCents(lines)).isEqualTo(2999 + 250);
    }

    @Test
    void lineAmountCents_roundsAHalfCentUpToTheNextCent() {
        // 0.5 units at 5 cents/unit = 2.5 cents - HALF_UP rounds to 3.
        long amount = InvoiceCalculator.lineAmountCents(new BigDecimal("0.5"), 5);

        assertThat(amount).isEqualTo(3);
    }

    @Test
    void lineAmountCents_withFractionalQuantityBelowOneUnit_stillCharges() {
        // 0.4 of a unit at 1000 cents/unit is 400 cents - rounding the
        // product, not the quantity, means this isn't lost to zero.
        long amount = InvoiceCalculator.lineAmountCents(new BigDecimal("0.4"), 1000);

        assertThat(amount).isEqualTo(400);
    }
}
