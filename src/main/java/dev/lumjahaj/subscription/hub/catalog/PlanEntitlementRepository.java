package dev.lumjahaj.subscription.hub.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlanEntitlementRepository extends JpaRepository<PlanEntitlementEntity, UUID> {
    Page<PlanEntitlementEntity> findByTenantIdAndPlanId(String tenantId, UUID planId, Pageable pageable);
}
