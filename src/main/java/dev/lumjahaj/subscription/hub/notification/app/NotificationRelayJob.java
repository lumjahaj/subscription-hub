package dev.lumjahaj.subscription.hub.notification.app;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationMessage;
import dev.lumjahaj.subscription.hub.notification.domain.NotificationPublisher;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Moves PENDING outbox rows onto the queue. Thin trigger only, like
 * BillingCycleJob and DunningJob: find the work, claim it in a short
 * transaction, publish with none open, record the result in a second.
 *
 * A publish failure (the queue unreachable) simply leaves the row PENDING —
 * logged, not thrown further — so the next tick picks it up again. Nothing
 * is lost; at worst delivery is delayed by one relay interval.
 */
@Component
public class NotificationRelayJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRelayJob.class);
    private static final int BATCH_SIZE = 100;

    private final TenantRepository tenants;
    private final NotificationRelayService relayService;
    private final NotificationPublisher publisher;

    public NotificationRelayJob(
            TenantRepository tenants,
            NotificationRelayService relayService,
            NotificationPublisher publisher
    ) {
        this.tenants = tenants;
        this.relayService = relayService;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${notification.relay.delay}")
    public void run() {
        for (Tenant tenant : tenants.findAllActive()) {
            TenantContext.runAs(tenant.id(), () -> processTenant(tenant.id()));
        }
    }

    private void processTenant(String tenantId) {
        MDC.put(MdcKeys.TENANT_ID, tenantId);
        try {
            for (UUID id : relayService.claimPending(tenantId, BATCH_SIZE)) {
                publishOneSafely(tenantId, id);
            }
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
        }
    }

    private void publishOneSafely(String tenantId, UUID id) {
        try {
            publisher.publish(new NotificationMessage(tenantId, id));
            relayService.markPublished(tenantId, id);
        } catch (Exception ex) {
            log.error("Failed to publish notification {}; it stays PENDING for the next run", id, ex);
        }
    }
}
