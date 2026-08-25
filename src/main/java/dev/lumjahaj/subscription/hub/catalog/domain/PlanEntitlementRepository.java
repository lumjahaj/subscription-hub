package dev.lumjahaj.subscription.hub.catalog.domain;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntitlementEntity;

import java.util.List;
import java.util.UUID;


public interface PlanEntitlementRepository {
    PlanEntitlementEntity save(PlanEntitlementEntity entitlement);
    List<PlanEntitlementEntity> findByTenantIdAndPlanId(String tenantId, UUID planId);
}