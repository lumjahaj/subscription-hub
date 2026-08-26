package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.common.logging.MdcKeys;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Thin trigger only — has no business logic of its own. Loops active
 * tenants, scoping TenantContext per tenant the way TenantResolverFilter
 * does per request (there's no request here, so this has to do it
 * itself), then finds and delegates each due subscription to
 * SubscriptionRenewalService one at a time.
 *
 * This is a reconciliation sweep, not the only way a renewal will ever
 * happen: once Stripe webhooks exist, they'll call
 * SubscriptionRenewalService.renewIfDue directly for the one subscription
 * they already know changed. Real systems keep a job like this running
 * even then, since webhooks can be missed or delayed.
 */
@Component
public class RenewalJob {

    private static final Logger log = LoggerFactory.getLogger(RenewalJob.class);
    private static final Set<SubscriptionStatus> DUE_STATUSES =
            EnumSet.of(SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE);

    private final TenantRepository tenants;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionRenewalService renewalService;

    public RenewalJob(
            TenantRepository tenants,
            SubscriptionRepository subscriptions,
            SubscriptionRenewalService renewalService
    ) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.renewalService = renewalService;
    }

    @Scheduled(cron = "${subscription.renewal.cron}")
    public void run() {
        Instant now = Instant.now();
        for (Tenant tenant : tenants.findAllActive()) {
            TenantContext.runAs(tenant.id(), () -> processTenant(tenant.id(), now));
        }
    }

    private void processTenant(String tenantId, Instant now) {
        MDC.put(MdcKeys.TENANT_ID, tenantId);
        try {
            List<SubscriptionEntity> due = subscriptions.findByTenantIdAndStatusInAndNextRenewalLessThanEqual(
                    tenantId, DUE_STATUSES, now);
            for (SubscriptionEntity subscription : due) {
                renewOneSafely(subscription.getId(), now);
            }
        } finally {
            MDC.remove(MdcKeys.TENANT_ID);
        }
    }

    /**
     * Each renewIfDue call is its own transaction, so one bad row (e.g. a
     * plan with an interval BillingPeriods doesn't recognize) rolls back
     * only that subscription and must not abort the rest of the tenant's
     * batch.
     */
    private void renewOneSafely(UUID subscriptionId, Instant now) {
        try {
            renewalService.renewIfDue(subscriptionId, now);
        } catch (Exception ex) {
            log.error("Renewal failed for subscription {}", subscriptionId, ex);
        }
    }
}
