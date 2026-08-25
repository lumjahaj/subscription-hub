package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import dev.lumjahaj.subscription.hub.catalog.domain.PlanEntitlementRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class PlanEntitlementRepositoryImpl implements PlanEntitlementRepository {

    private final PlanEntitlementJpaRepository jpaRepository;

    public PlanEntitlementRepositoryImpl(PlanEntitlementJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PlanEntitlementEntity save(PlanEntitlementEntity entitlement) {
        return jpaRepository.save(entitlement);
    }

    @Override
    public List<PlanEntitlementEntity> findByTenantIdAndPlanId(String tenantId, UUID planId) {
        return jpaRepository.findByTenantIdAndPlanId(tenantId, planId);
    }
}