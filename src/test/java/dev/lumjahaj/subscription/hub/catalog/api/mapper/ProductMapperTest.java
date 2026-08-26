package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductResponse;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest extends MapperValidationSupport {

    @Test
    void toEntity_mapsAllFieldsFromRequest() {
        var request = new ProductCreateRequest("messaging", "Messaging API", "Messaging platform");

        ProductEntity entity = ProductMapper.toEntity(request);

        assertThat(entity.getCode()).isEqualTo("messaging");
        assertThat(entity.getName()).isEqualTo("Messaging API");
        assertThat(entity.getDescription()).isEqualTo("Messaging platform");
    }

    @Test
    void toEntity_doesNotSetTenantId() {
        // tenantId is the caller's (app service's) responsibility, not the mapper's
        var request = new ProductCreateRequest("messaging", "Messaging API", null);

        ProductEntity entity = ProductMapper.toEntity(request);

        assertThat(entity.getTenantId()).isNull();
    }

    @Test
    void request_rejectsBlankCode() {
        var request = new ProductCreateRequest("", "Messaging API", "desc");

        Set<ConstraintViolation<ProductCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void toResponse_mapsAllFieldsFromEntity() {
        ProductEntity entity = new ProductEntity();
        entity.setId(UUID.randomUUID());
        entity.setCode("messaging");
        entity.setName("Messaging API");
        entity.setDescription("Messaging platform");
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));

        ProductResponse response = ProductMapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(entity.getId());
        assertThat(response.code()).isEqualTo("messaging");
        assertThat(response.name()).isEqualTo("Messaging API");
        assertThat(response.description()).isEqualTo("Messaging platform");
        assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void request_allowsNullDescription() {
        var request = new ProductCreateRequest("messaging", "Messaging API", null);

        Set<ConstraintViolation<ProductCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }
}