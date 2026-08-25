package dev.lumjahaj.subscription.hub.catalog.infra.jpa;

import dev.lumjahaj.subscription.hub.catalog.domain.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpaRepository;

    public ProductRepositoryImpl(ProductJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ProductEntity save(ProductEntity product) {
        return jpaRepository.save(product);
    }

    @Override
    public Optional<ProductEntity> findByTenantIdAndCode(String tenantId, String code) {
        return jpaRepository.findByTenantIdAndCode(tenantId, code);
    }

    @Override
    public Page<ProductEntity> findByTenantId(String tenantId, Pageable pageable) {
        return jpaRepository.findByTenantId(tenantId, pageable);
    }
}