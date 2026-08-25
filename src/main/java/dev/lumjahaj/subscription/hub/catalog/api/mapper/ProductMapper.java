package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;

/**
 * Manual mapper between the Product API DTOs and ProductEntity.
 *
 * Kept as plain static methods rather than a Spring @Component: there's no
 * state, no dependencies to inject, and nothing here needs to be mocked in
 * isolation — the mapping logic itself is what a test would want to run for
 * real anyway. If a mapper elsewhere in the app grows dependencies (e.g.
 * needs to look something up to populate a field), that one should become a
 * @Component instead. No need to apply that pattern everywhere by default.
 */
public final class ProductMapper {

    private ProductMapper() {
    }

    /**
     * Builds a new ProductEntity from a create request.
     * Caller is responsible for setting tenantId (from the tenant context)
     * before persisting — this mapper only knows about the request payload.
     */
    public static ProductEntity toEntity(ProductCreateRequest request) {
        ProductEntity entity = new ProductEntity();
        entity.setCode(request.code());
        entity.setName(request.name());
        entity.setDescription(request.description());
        return entity;
    }

    public static ProductResponse toResponse(ProductEntity entity) {
        return new ProductResponse(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}