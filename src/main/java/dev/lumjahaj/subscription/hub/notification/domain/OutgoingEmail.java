package dev.lumjahaj.subscription.hub.notification.domain;

import java.util.Optional;

/**
 * What {@link NotificationSender} actually sends: an HTML body with a
 * plain-text alternative, and an optional attachment (the invoice PDF).
 */
public record OutgoingEmail(
        String recipient,
        String subject,
        String html,
        String text,
        Optional<Attachment> attachment
) {
}
