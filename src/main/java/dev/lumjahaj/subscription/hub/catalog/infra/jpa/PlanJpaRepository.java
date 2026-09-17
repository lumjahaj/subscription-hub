package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanJpaRepository extends JpaRepository<PlanEntity, UUID> {

    // product is fetched with the plan because PlanMapper reads its code, and
    // the mapper runs in the controller, after the transaction has ended. A
    // many-to-one join adds no rows, so the page size and count are unchanged.
    @EntityGraph(attributePaths = "product")
    Optional<PlanEntity> findByTenantIdAndCode(String tenantId, String code);

    @EntityGraph(attributePaths = "product")
    Page<PlanEntity> findByTenantId(String tenantId, Pageable pageable);
}
