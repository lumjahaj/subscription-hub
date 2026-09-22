package dev.lumjahaj.subscription.hub.notification.domain;

/**
 * What happened, not what template rendered it - kept separate from
 * {@link EmailTemplate} so a template can be redesigned without touching
 * the stored rows, and so the dedup key format has something stable to
 * switch on.
 */
public enum NotificationType {
    INVOICE_ISSUED,
    PAYMENT_FAILED,
    PAYMENT_RECOVERED,
    PAYMENT_METHOD_REQUIRED,
    SUBSCRIPTION_CANCELED
}
