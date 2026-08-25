package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanEntitlementJpaRepository extends JpaRepository<PlanEntitlementEntity, UUID> {
    List<PlanEntitlementEntity> findByTenantIdAndPlanId(String tenantId, UUID planId);
}