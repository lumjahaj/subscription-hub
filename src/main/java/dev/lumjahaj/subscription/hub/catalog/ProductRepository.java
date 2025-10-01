package dev.lumjahaj.subscription.hub.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    Page<ProductEntity> findByTenantId(String tenantId, Pageable pageable);
    Optional<ProductEntity> findByTenantIdAndCode(String tenantId, String code);
}

