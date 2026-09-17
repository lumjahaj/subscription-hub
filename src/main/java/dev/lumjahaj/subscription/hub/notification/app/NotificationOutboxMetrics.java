package dev.lumjahaj.subscription.hub.notification.app;

import dev.lumjahaj.subscription.hub.notification.domain.NotificationRepository;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * notification.outbox.oldest.age (status): how long the oldest undelivered
 * notification has been waiting, in seconds.
 *
 * Counters cannot show a stuck outbox - a relay that stopped publishes
 * nothing, and nothing looks like zero. The age of the oldest waiting row
 * grows without bound instead, which is alertable. Two statuses, because they
 * are two different failures:
 * - PENDING growing: the relay is not publishing (job stopped, queue down).
 * - PUBLISHED growing: messages were published and never delivered - SMTP is
 *   failing, or they have been dead-lettered. Nothing reads notifications-dlq,
 *   so this gauge is the only thing that will ever notice one.
 *
 * Read from the database on every scrape (one aggregate per status every
 * scrape interval), rather than cached by the relay job, so the value stays
 * honest precisely when the job is the thing that stopped. No existing index
 * serves the query (the outbox index leads with tenant_id); see the state
 * skill's Observability gaps.
 */
@Component
class NotificationOutboxMetrics {

    NotificationOutboxMetrics(NotificationRepository notifications, MeterRegistry registry) {
        for (NotificationStatus status : new NotificationStatus[] {NotificationStatus.PENDING, NotificationStatus.PUBLISHED}) {
            Gauge.builder("notification.outbox.oldest.age",
                            () -> notifications.oldestAgeSecondsAcrossActiveTenants(status))
                    .description("Age of the oldest notification waiting in this status, across active tenants")
                    .baseUnit("seconds")
                    .tag("status", status.name())
                    .register(registry);
        }
    }
}
