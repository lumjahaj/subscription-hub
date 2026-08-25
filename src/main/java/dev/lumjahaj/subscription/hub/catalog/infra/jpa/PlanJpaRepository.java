package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanJpaRepository extends JpaRepository<PlanEntity, UUID> {
    Optional<PlanEntity> findByTenantIdAndCode(String tenantId, String code);
    Page<PlanEntity> findByTenantId(String tenantId, Pageable pageable);
}