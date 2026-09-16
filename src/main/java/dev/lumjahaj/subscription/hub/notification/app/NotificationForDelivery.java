package dev.lumjahaj.subscription.hub.notification.app;

import java.util.UUID;

/**
 * Everything {@link NotificationDeliveryService} needs, copied out of
 * {@code NotificationEntity} while its loading transaction is still open.
 *
 * The entity itself cannot cross into the no-transaction section that
 * follows: {@code invoice} is a lazy association, and touching it after the
 * session closes would throw. Copying the handful of plain fields (and
 * just the invoice's id, not the entity) avoids that without widening the
 * transaction to cover the SMTP call.
 */
record NotificationForDelivery(
        String recipient,
        String subject,
        String html,
        String text,
        UUID invoiceId
) {
}
