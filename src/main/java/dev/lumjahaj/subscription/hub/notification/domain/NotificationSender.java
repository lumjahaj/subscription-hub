package dev.lumjahaj.subscription.hub.notification.domain;

/**
 * Delivers one email. The only adapter, {@code SmtpNotificationSender}, talks
 * to Mailpit locally and to Amazon SES's SMTP interface in production - a
 * host change, not a code change.
 */
public interface NotificationSender {

    void send(OutgoingEmail email);
}
