package dev.lumjahaj.subscription.hub.payment.domain;

/**
 * The provider could not be reached or refused the request. Deliberately
 * not a business-rule exception: the domain doesn't know about HTTP
 * statuses, so the app layer translates this into a 503.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
