package dev.lumjahaj.subscription.hub.notification.app;

/**
 * Wraps a failed delivery attempt. Deliberately unchecked and deliberately
 * rethrown by {@code NotificationDeliveryService.deliver} after recording
 * the failure: {@code SqsNotificationListener} must see it, so the message
 * stays unacknowledged and SQS redelivers it after the visibility timeout.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
