package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * payments.pending.oldest.age: how long the oldest unsettled payment has been
 * waiting, in seconds.
 *
 * This is the escalation path reconciliation deliberately leaves open. A
 * payment the provider cannot be reached for is never guessed at — it stays
 * PENDING rather than be marked failed, because marking it failed would free
 * its invoice's slot in ux_payment_invoice_in_flight_or_succeeded and let
 * dunning charge again under a new idempotency key, double-charging a
 * customer whose card may already have been debited. The cost of that
 * caution is a row nothing will ever resolve on its own, and a counter cannot
 * show it: nothing failing looks exactly like nothing happening. The age of
 * the oldest one grows without bound instead, which is alertable.
 *
 * Read from the database on every scrape rather than cached by the job, so
 * the value stays honest precisely when the job is the thing that stopped —
 * the same reasoning, and the same shape, as NotificationOutboxMetrics.
 * Backed by idx_payment_pending_created (V18), which is partial on PENDING
 * and so stays small.
 */
@Component
class PaymentPendingMetrics {

    PaymentPendingMetrics(PaymentRepository payments, MeterRegistry registry) {
        Gauge.builder("payments.pending.oldest.age", payments::oldestPendingAgeSecondsAcrossActiveTenants)
                .description("Age of the oldest payment still awaiting an outcome, across active tenants")
                .baseUnit("seconds")
                .register(registry);
    }
}
