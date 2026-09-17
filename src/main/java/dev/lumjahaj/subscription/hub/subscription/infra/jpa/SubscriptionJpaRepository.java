package dev.lumjahaj.subscription.hub.subscription.infra.jpa;

import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, UUID> {

    // plan is fetched on the finders behind API responses, because
    // SubscriptionMapper reads planCode after the transaction has ended
    // (see PlanJpaRepository). customer is not: the mapper reads only its id,
    // which a Hibernate proxy answers without loading anything.
    @EntityGraph(attributePaths = "plan")
    Page<SubscriptionEntity> findByTenantId(String tenantId, Pageable pageable);

    @EntityGraph(attributePaths = "plan")
    Page<SubscriptionEntity> findByTenantIdAndCustomerId(String tenantId, UUID customerId, Pageable pageable);

    @EntityGraph(attributePaths = "plan")
    Optional<SubscriptionEntity> findByTenantIdAndId(String tenantId, UUID id);

    // BillingCycleJob reads only the ids and reloads each one in its own
    // transaction, so this stays a plain query.
    List<SubscriptionEntity> findByTenantIdAndStatusInAndNextRenewalLessThanEqual(
            String tenantId, Collection<SubscriptionStatus> statuses, Instant cutoff);
}
