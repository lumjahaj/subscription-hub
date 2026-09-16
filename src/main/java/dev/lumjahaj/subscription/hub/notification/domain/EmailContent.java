package dev.lumjahaj.subscription.hub.notification.domain;

/**
 * A rendered email, ready to store or send: subject plus both bodies.
 * Rendering happens once, at enqueue time, and the result is stored
 * verbatim on the notification row - see {@code V14__add_notification_outbox.sql}.
 */
public record EmailContent(String subject, String html, String text) {
}
