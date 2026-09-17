package dev.lumjahaj.subscription.hub.subscription.app;

import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final CustomerRepository customers;
    private final PlanRepository plans;
    private final AuditService audit;

    public SubscriptionService(
            SubscriptionRepository subscriptions,
            CustomerRepository customers,
            PlanRepository plans,
            AuditService audit
    ) {
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.plans = plans;
        this.audit = audit;
    }

    @Transactional
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
            Instant periodEnd = BillingPeriods.addInterval(now, plan.getIntervalUnit(), plan.getIntervalCount());
            entity.setStatus(SubscriptionStatus.ACTIVE);
            entity.setCurrentPeriodEnd(periodEnd);
            entity.setNextRenewal(periodEnd);
        }

        SubscriptionEntity saved = subscriptions.save(entity);
        audit.record(AuditEventType.SUBSCRIPTION_CREATED, saved.getId(), Map.of(
                "customerId", customer.getId(),
                "planCode", plan.getCode(),
                "status", saved.getStatus()));
        return saved;
    }

    @Transactional
    public SubscriptionEntity cancel(UUID id) {
        SubscriptionEntity subscription = findOwned(id);
        if (subscription.getStatus() == SubscriptionStatus.CANCELED) {
            throw new InvalidSubscriptionStateException("cancel", subscription.getStatus());
        }
        SubscriptionStatus from = subscription.getStatus();
        subscription.setStatus(SubscriptionStatus.CANCELED);
        subscription.setCanceledAt(Instant.now());
        SubscriptionEntity saved = subscriptions.save(subscription);
        audit.record(AuditEventType.SUBSCRIPTION_CANCELED, saved.getId(), Map.of("from", from));
        return saved;
    }

    @Transactional
    public SubscriptionEntity pause(UUID id) {
        SubscriptionEntity subscription = findOwned(id);
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.TRIALING) {
            throw new InvalidSubscriptionStateException("pause", subscription.getStatus());
        }
        SubscriptionStatus from = subscription.getStatus();
        subscription.setStatus(SubscriptionStatus.PAUSED);
        SubscriptionEntity saved = subscriptions.save(subscription);
        audit.record(AuditEventType.SUBSCRIPTION_PAUSED, saved.getId(), Map.of("from", from));
        return saved;
    }

    @Transactional
    public SubscriptionEntity resume(UUID id) {
        SubscriptionEntity subscription = findOwned(id);
        if (subscription.getStatus() != SubscriptionStatus.PAUSED) {
            throw new InvalidSubscriptionStateException("resume", subscription.getStatus());
        }
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        SubscriptionEntity saved = subscriptions.save(subscription);
        audit.record(AuditEventType.SUBSCRIPTION_RESUMED, saved.getId(), Map.of());
        return saved;
    }

    public SubscriptionEntity getById(UUID id) {
        return findOwned(id);
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
}
