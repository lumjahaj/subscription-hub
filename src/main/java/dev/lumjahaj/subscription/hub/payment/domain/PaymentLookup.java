package dev.lumjahaj.subscription.hub.payment.domain;

/**
 * What an adapter needs to ask the provider about a payment we already
 * started: the request we originally sent, plus the provider's reference if
 * we ever managed to record one.
 *
 * The whole {@link PaymentRequest} travels rather than just the id because a
 * null {@code providerReference} means the create call never returned, and
 * the only safe way to find out whether it landed is to re-issue it under the
 * same idempotency key — which needs the amount, currency and method again.
 *
 * @param providerReference the provider's id for the payment, or null when
 *                          the create call never returned one
 */
public record PaymentLookup(
        PaymentRequest request,
        String providerReference
) {
}
