package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanEntitlementJpaRepository extends JpaRepository<PlanEntitlementEntity, UUID> {

    // plan is fetched because PlanEntitlementMapper reads its code outside
    // the transaction (see PlanJpaRepository).
    @EntityGraph(attributePaths = "plan")
    List<PlanEntitlementEntity> findByTenantIdAndPlanId(String tenantId, UUID planId);

    Optional<PlanEntitlementEntity> findByTenantIdAndPlanIdAndKey(String tenantId, UUID planId, String key);
}
