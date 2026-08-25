package dev.lumjahaj.subscription.hub.catalog.domain;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface ProductRepository {
    ProductEntity save(ProductEntity product);
    Optional<ProductEntity> findByTenantIdAndCode(String tenantId, String code);
    Page<ProductEntity> findByTenantId(String tenantId, Pageable pageable);
}
