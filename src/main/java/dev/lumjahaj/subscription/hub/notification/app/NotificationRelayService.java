package dev.lumjahaj.subscription.hub.notification.app;

import dev.lumjahaj.subscription.hub.notification.domain.NotificationRepository;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationStatus;
import dev.lumjahaj.subscription.hub.notification.infra.jpa.NotificationEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The two short transactions around the relay's queue publish, which must
 * happen with no transaction open (the same "no remote call inside a
 * transaction" rule as PaymentService and InvoicePdfService). Both methods
 * are called directly by NotificationRelayJob, never by each other on this
 * bean, so the publish in between always runs through the job — the same
 * separation DunningService.startAttempt documents for the same reason
 * (a method called on "this" bypasses the transactional proxy).
 */
@Service
public class NotificationRelayService {

    private final NotificationRepository notifications;

    public NotificationRelayService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<UUID> claimPending(String tenantId, int batchSize) {
        return notifications.findByTenantIdAndStatus(tenantId, NotificationStatus.PENDING, PageRequest.of(0, batchSize))
                .stream()
                .map(NotificationEntity::getId)
                .toList();
    }

    @Transactional
    public void markPublished(String tenantId, UUID id) {
        notifications.findByTenantIdAndId(tenantId, id).ifPresent(notification -> {
            notification.setStatus(NotificationStatus.PUBLISHED);
            notification.setPublishedAt(Instant.now());
            notifications.save(notification);
        });
    }
}
