package dev.lumjahaj.subscription.hub.notification.domain;

/**
 * PENDING -> PUBLISHED -> SENT. There is deliberately no FAILED here: SQS's
 * redrive policy is the retry mechanism, and a message the listener keeps
 * failing to deliver ends up on the dead-letter queue, not in this column.
 * A row that stays PENDING or PUBLISHED for a long time is the sign
 * something is stuck, not a terminal failure.
 */
public enum NotificationStatus {
    PENDING,
    PUBLISHED,
    SENT
}
