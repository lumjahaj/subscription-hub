package dev.lumjahaj.subscription.hub.payment.domain;

/**
 * Where provider events are delivered. Implemented by
 * PaymentSettlementService in the app layer.
 *
 * A port rather than a direct call because the in-process fake gateway
 * lives in infra and has to deliver events inward: infra depends on this
 * interface, never on the app layer (api → app → domain ← infra).
 */
public interface PaymentEventHandler {

    void handle(PaymentEvent event);
}
