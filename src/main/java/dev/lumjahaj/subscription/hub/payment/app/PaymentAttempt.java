package dev.lumjahaj.subscription.hub.payment.app;

import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;

/**
 * @param created false when the Idempotency-Key matched an earlier request,
 *                so the controller answers 200 with that payment rather
 *                than 201
 */
public record PaymentAttempt(PaymentEntity payment, boolean created) {
}
