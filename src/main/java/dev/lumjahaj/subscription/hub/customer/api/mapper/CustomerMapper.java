package dev.lumjahaj.subscription.hub.customer.api.mapper;

import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerResponse;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;

public final class CustomerMapper {

    private CustomerMapper() {
    }

    public static CustomerEntity toEntity(CustomerCreateRequest request) {
        CustomerEntity entity = new CustomerEntity();
        entity.setExternalId(request.externalId());
        entity.setEmail(request.email());
        entity.setName(request.name());
        return entity;
    }

    public static CustomerResponse toResponse(CustomerEntity entity) {
        return new CustomerResponse(
                entity.getId(),
                entity.getExternalId(),
                entity.getEmail(),
                entity.getName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
