package dev.lumjahaj.subscription.hub.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<PlanEntity, UUID> {
    Page<PlanEntity> findByTenantId(String tenantId, Pageable pageable);
    Optional<PlanEntity> findByTenantIdAndCode(String tenantId, String code);
}
