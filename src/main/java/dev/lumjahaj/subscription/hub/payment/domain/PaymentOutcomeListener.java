package dev.lumjahaj.subscription.hub.payment.domain;

import java.util.UUID;

/**
 * Notified when a payment settles, so other modules can react without the
 * payment module knowing they exist.
 *
 * This is how dunning hears about a failed charge while the dependency
 * still points dunning → payment: payment defines the port, dunning
 * implements it. Reversing that would give the payment module opinions
 * about subscription lifecycles, and ArchUnit would reject the cycle.
 *
 * Implementations run inside the settling transaction, so whatever they
 * change commits — or rolls back — with the payment itself. They must not
 * make remote calls.
 */
public interface PaymentOutcomeListener {

    void onPaymentSucceeded(UUID invoiceId);

    void onPaymentFailed(UUID invoiceId, String failureCode);
}
