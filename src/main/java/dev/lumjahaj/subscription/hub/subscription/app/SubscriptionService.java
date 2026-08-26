package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.catalog.domain.PlanRepository;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.common.api.ResourceNotFoundException;
import dev.lumjahaj.subscription.hub.customer.domain.CustomerRepository;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionRepository;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final CustomerRepository customers;
    private final PlanRepository plans;

    public SubscriptionService(
            SubscriptionRepository subscriptions,
            CustomerRepository customers,
            PlanRepository plans
    ) {
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.plans = plans;
    }

    public SubscriptionEntity create(SubscriptionCreateRequest request) {
        String tenantId = TenantContext.getTenantId();

        CustomerEntity customer = customers.findByTenantIdAndId(tenantId, request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.customerId().toString()));

        PlanEntity plan = plans.findByTenantIdAndCode(tenantId, request.planCode())
                .orElseThrow(() -> new ResourceNotFoundException("Plan", request.planCode()));

        Instant now = Instant.now();

        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setTenantId(tenantId);
        entity.setCustomer(customer);
        entity.setPlan(plan);
        entity.setStartAt(now);
        entity.setCurrentPeriodStart(now);

        if (plan.getTrialDays() > 0) {
            Instant trialEnd = now.plus(java.time.Duration.ofDays(plan.getTrialDays()));
            entity.setStatus(SubscriptionStatus.TRIALING);
            entity.setCurrentPeriodEnd(trialEnd);
            entity.setNextRenewal(trialEnd);
        } else {
            Instant periodEnd = addInterval(now, plan.getInterval());
            entity.setStatus(SubscriptionStatus.ACTIVE);
            entity.setCurrentPeriodEnd(periodEnd);
            entity.setNextRenewal(periodEnd);
        }

        return subscriptions.save(entity);
    }

    public SubscriptionEntity cancel(UUID id) {
        SubscriptionEntity subscription = findOwned(id);
        if (subscription.getStatus() == SubscriptionStatus.CANCELED) {
            throw new InvalidSubscriptionStateException("cancel", subscription.getStatus());
        }
        subscription.setStatus(SubscriptionStatus.CANCELED);
        subscription.setCanceledAt(Instant.now());
        return subscriptions.save(subscription);
    }

    public SubscriptionEntity pause(UUID id) {
        SubscriptionEntity subscription = findOwned(id);
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.TRIALING) {
            throw new InvalidSubscriptionStateException("pause", subscription.getStatus());
        }
        subscription.setStatus(SubscriptionStatus.PAUSED);
        return subscriptions.save(subscription);
    }

    public SubscriptionEntity resume(UUID id) {
        SubscriptionEntity subscription = findOwned(id);
        if (subscription.getStatus() != SubscriptionStatus.PAUSED) {
            throw new InvalidSubscriptionStateException("resume", subscription.getStatus());
        }
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        return subscriptions.save(subscription);
    }

    private SubscriptionEntity findOwned(UUID id) {
        String tenantId = TenantContext.getTenantId();
        return subscriptions.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", id.toString()));
    }

    public Page<SubscriptionEntity> list(Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return subscriptions.findByTenantId(tenantId, pageable);
    }

    public Page<SubscriptionEntity> listByCustomer(UUID customerId, Pageable pageable) {
        String tenantId = TenantContext.getTenantId();
        return subscriptions.findByTenantIdAndCustomerId(tenantId, customerId, pageable);
    }

    /**
     * Instant has no notion of "a month" or "a year" (those are
     * calendar-based, not fixed-duration) — plus(1, ChronoUnit.MONTHS)
     * on an Instant throws UnsupportedTemporalTypeException. Converting
     * to ZonedDateTime (UTC) first gives access to plusMonths/plusYears,
     * then converting back to Instant for storage.
     */
    private Instant addInterval(Instant start, String interval) {
        ZonedDateTime zdt = start.atZone(ZoneOffset.UTC);
        ZonedDateTime end = switch (interval) {
            case "MONTH" -> zdt.plusMonths(1);
            case "YEAR" -> zdt.plusYears(1);
            default -> throw new IllegalStateException("Unknown plan interval: " + interval);
        };
        return end.toInstant();
    }
}
