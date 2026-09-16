package dev.lumjahaj.subscription.hub.dunning.app;

/**
 * One collection attempt, already recorded against the invoice's schedule
 * and ready to be sent to the provider.
 *
 * @param number         which attempt this is, for logging
 * @param paymentMethod  the customer's stored provider token
 * @param idempotencyKey derived from the invoice and attempt number, so a
 *                       repeated run cannot charge the same attempt twice
 */
public record DunningAttempt(int number, String paymentMethod, String idempotencyKey) {
}
