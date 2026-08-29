package dev.lumjahaj.subscription.hub.usage.app;

import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.subscription.app.InvalidSubscriptionStateException;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageRecordRequest;
import dev.lumjahaj.subscription.hub.usage.domain.UsageCounterRepository;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class UsageService {

    private final UsageCounterRepository usageCounters;
    private final SubscriptionRepository subscriptions;

    public UsageService(UsageCounterRepository usageCounters, SubscriptionRepository subscriptions) {
        this.usageCounters = usageCounters;
        this.subscriptions = subscriptions;
    }

    /**
     * period_start/period_end are read off the subscription, not accepted
     * from the request - a client can't backdate usage into a period
     * renewal has already rolled past, and this is what makes the
     * (tenant, subscription, meter, period_start) unique key meaningful.
     *
     * A CANCELED subscription has no live period left to attribute usage
     * to. PAUSED/PAST_DUE/TRIALING can still accrue usage that gets
     * billed once the subscription resumes or pays - only CANCELED is a
     * dead end.
     */
    public UsageCounterEntity record(UUID subscriptionId, UsageRecordRequest request) {
        String tenantId = TenantContext.getTenantId();
        SubscriptionEntity subscription = findOwnedSubscription(tenantId, subscriptionId);

        if (subscription.getStatus() == SubscriptionStatus.CANCELED) {
            throw new InvalidSubscriptionStateException("record usage for", subscription.getStatus());
        }

        return usageCounters.upsertAndIncrement(
                tenantId, subscriptionId, request.meterKey(),
                subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd(), request.amount());
    }

    public List<UsageCounterEntity> list(UUID subscriptionId) {
        String tenantId = TenantContext.getTenantId();
        findOwnedSubscription(tenantId, subscriptionId);
        return usageCounters.findByTenantIdAndSubscriptionId(tenantId, subscriptionId);
    }

    private SubscriptionEntity findOwnedSubscription(String tenantId, UUID subscriptionId) {
        return subscriptions.findByTenantIdAndId(tenantId, subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", subscriptionId.toString()));
    }
}
