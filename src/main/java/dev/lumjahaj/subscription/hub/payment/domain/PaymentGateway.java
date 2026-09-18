package dev.lumjahaj.subscription.hub.payment.domain;

import java.util.Optional;

/**
 * The port a payment provider adapter implements.
 *
 * Shaped after Stripe's PaymentIntent model rather than a simple
 * "charge and tell me the result": creating a payment only returns the
 * provider's reference for it, never the outcome. The outcome arrives
 * later, as a {@link PaymentEvent} delivered to {@link PaymentEventHandler}
 * — a webhook for a real provider. A synchronous port would have to be
 * rebuilt the day a real provider is plugged in.
 */
public interface PaymentGateway {

    /** Stored on each payment, so one provider's events never settle another's payments. */
    String provider();

    /**
     * Asks the provider to collect a payment.
     *
     * Implementations must pass {@link PaymentRequest#paymentId()} to the
     * provider as its idempotency key, so calling this again for the same
     * payment (after a timeout or crash) returns the same provider payment
     * instead of charging twice.
     *
     * @return the provider's reference for the payment
     * @throws PaymentGatewayException if the provider could not be reached
     *         or refused the request outright; the outcome is then unknown
     */
    String createPayment(PaymentRequest request);

    /**
     * Asks the provider what became of a payment we started but never saw an
     * outcome for — the event was lost, or the create call never returned.
     *
     * This only <em>discovers</em> an outcome. Applying it stays with
     * PaymentSettlementService, reached through the same {@link PaymentEvent}
     * a webhook produces, so reconciliation is not a second way to settle
     * money.
     *
     * Implementations must not report an outcome they did not get from the
     * provider. In particular, a payment with no {@code providerReference}
     * must not be assumed never to have been created: the create call can
     * time out after the customer was charged. Re-issue it under the same
     * idempotency key instead, which returns the original payment if one
     * exists and charges exactly once either way.
     *
     * @return the outcome if the provider has reached one, or empty if the
     *         payment is still in progress there — ask again next run
     * @throws PaymentGatewayException if the provider could not be reached.
     *         The caller leaves the payment alone: "unreachable" is not
     *         "failed", and guessing here either loses money or charges twice.
     */
    Optional<PaymentEvent> reconcile(PaymentLookup lookup);
}
