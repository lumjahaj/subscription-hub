package dev.lumjahaj.subscription.hub.notification.domain;

/**
 * Publishes a notification onto the queue. The only adapter,
 * {@code SqsNotificationPublisher}, sends it to SQS (ElasticMQ locally and
 * in tests); a publish failure must leave the caller's row alone so the
 * relay retries it on its next tick.
 */
public interface NotificationPublisher {

    void publish(NotificationMessage message);
}
