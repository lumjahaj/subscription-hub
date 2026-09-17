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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

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
        return transition(id, "cancel", CANCELABLE, from -> {
            if (!subscriptions.cancelIfStatus(TenantContext.getTenantId(), id, from, Instant.now())) {
                return false;
            }
            audit.record(AuditEventType.SUBSCRIPTION_CANCELED, id, Map.of("from", from));
            return true;
        });
    }

    @Transactional
    public SubscriptionEntity pause(UUID id) {
        return transition(id, "pause", PAUSABLE, from -> {
            if (!subscriptions.updateStatusIfStatus(TenantContext.getTenantId(), id, from, SubscriptionStatus.PAUSED)) {
                return false;
            }
            audit.record(AuditEventType.SUBSCRIPTION_PAUSED, id, Map.of("from", from));
            return true;
        });
    }

    @Transactional
    public SubscriptionEntity resume(UUID id) {
        return transition(id, "resume", RESUMABLE, from -> {
            if (!subscriptions.updateStatusIfStatus(TenantContext.getTenantId(), id, from, SubscriptionStatus.ACTIVE)) {
                return false;
            }
            audit.record(AuditEventType.SUBSCRIPTION_RESUMED, id, Map.of());
            return true;
        });
    }

    private static final Set<SubscriptionStatus> CANCELABLE = EnumSet.complementOf(EnumSet.of(SubscriptionStatus.CANCELED));
    private static final Set<SubscriptionStatus> PAUSABLE = EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING);
    private static final Set<SubscriptionStatus> RESUMABLE = EnumSet.of(SubscriptionStatus.PAUSED);
    private static final int MAX_TRANSITION_ATTEMPTS = 3;

    /**
     * A state command as a compare-and-set: check the transition is allowed from
     * the status just read, then apply it only if the row still has that status.
     *
     * When the conditional update misses, something else changed the
     * subscription in between - dunning marking it PAST_DUE, a job renewing it,
     * another request. The status is re-read from the database and the decision
     * is made again against it: a cancel that lost to PAST_DUE still cancels, a
     * pause that lost to a cancellation is refused as INVALID_SUBSCRIPTION_STATE.
     * Nothing is ever written over a status this request did not see, which is
     * what the old load-modify-save could not promise.
     *
     * The loop is bounded because every retry means another writer committed; a
     * subscription changing three times within one request is not a case worth
     * waiting out, so the caller gets 409 CONCURRENT_MODIFICATION and re-sends.
     */
    private SubscriptionEntity transition(UUID id, String action, Set<SubscriptionStatus> allowedFrom,
                                          Predicate<SubscriptionStatus> applyFrom) {
        String tenantId = TenantContext.getTenantId();
        SubscriptionEntity subscription = findOwned(id);
        for (int attempt = 0; attempt < MAX_TRANSITION_ATTEMPTS; attempt++) {
            SubscriptionStatus from = subscription.getStatus();
            if (!allowedFrom.contains(from)) {
                throw new InvalidSubscriptionStateException(action, from);
            }
            if (applyFrom.test(from)) {
                return subscription;
            }
            subscription = subscriptions.findCurrentByTenantIdAndId(tenantId, id)
                    .orElseThrow(() -> new ResourceNotFoundException("Subscription", id.toString()));
        }
        throw new OptimisticLockingFailureException("Subscription " + id + " kept changing while trying to " + action + " it");
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
