package dev.lumjahaj.subscription.hub.payment.domain;

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
}
