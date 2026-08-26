package dev.lumjahaj.subscription.hub.customer.api.mapper;

import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMapperTest extends MapperValidationSupport {

    @Test
    void toEntity_mapsAllFieldsFromRequest() {
        var request = new CustomerCreateRequest("ext-123", "jane@acme.com", "Jane Doe");

        CustomerEntity entity = CustomerMapper.toEntity(request);

        assertThat(entity.getExternalId()).isEqualTo("ext-123");
        assertThat(entity.getEmail()).isEqualTo("jane@acme.com");
        assertThat(entity.getName()).isEqualTo("Jane Doe");
    }

    @Test
    void toEntity_doesNotSetTenantId() {
        // tenantId is the caller's (app service's) responsibility, not the mapper's
        var request = new CustomerCreateRequest(null, "jane@acme.com", "Jane Doe");

        CustomerEntity entity = CustomerMapper.toEntity(request);

        assertThat(entity.getTenantId()).isNull();
    }

    @Test
    void toResponse_mapsAllFieldsFromEntity() {
        CustomerEntity entity = new CustomerEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId("ext-123");
        entity.setEmail("jane@acme.com");
        entity.setName("Jane Doe");
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-02T00:00:00Z"));

        CustomerResponse response = CustomerMapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(entity.getId());
        assertThat(response.externalId()).isEqualTo("ext-123");
        assertThat(response.email()).isEqualTo("jane@acme.com");
        assertThat(response.name()).isEqualTo("Jane Doe");
        assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void request_rejectsBlankEmail() {
        var request = new CustomerCreateRequest("ext-123", "", "Jane Doe");

        Set<ConstraintViolation<CustomerCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsMalformedEmail() {
        var request = new CustomerCreateRequest("ext-123", "not-an-email", "Jane Doe");

        Set<ConstraintViolation<CustomerCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_allowsNullExternalId() {
        var request = new CustomerCreateRequest(null, "jane@acme.com", "Jane Doe");

        Set<ConstraintViolation<CustomerCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }
}