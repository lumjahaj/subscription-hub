package dev.lumjahaj.subscription.hub.catalog.domain;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface PlanRepository {
    PlanEntity save(PlanEntity plan);
    Optional<PlanEntity> findByTenantIdAndCode(String tenantId, String code);
    Page<PlanEntity> findByTenantId(String tenantId, Pageable pageable);
}