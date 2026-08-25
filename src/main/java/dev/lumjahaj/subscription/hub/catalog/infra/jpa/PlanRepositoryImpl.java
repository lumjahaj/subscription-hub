package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import dev.lumjahaj.subscription.hub.catalog.domain.PlanRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PlanRepositoryImpl implements PlanRepository {

    private final PlanJpaRepository jpaRepository;

    public PlanRepositoryImpl(PlanJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PlanEntity save(PlanEntity plan) {
        return jpaRepository.save(plan);
    }

    @Override
    public Optional<PlanEntity> findByTenantIdAndCode(String tenantId, String code) {
        return jpaRepository.findByTenantIdAndCode(tenantId, code);
    }

    @Override
    public Page<PlanEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantId(tenantId, pageable);
    }
}