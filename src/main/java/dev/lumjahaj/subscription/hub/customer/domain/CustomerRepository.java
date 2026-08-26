package dev.lumjahaj.subscription.hub.customer.domain;

import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface CustomerRepository {
    CustomerEntity save(CustomerEntity customer);
    Optional<CustomerEntity> findByTenantIdAndEmail(String tenantId, String email);
    Page<CustomerEntity> findByTenantId(String tenantId, Pageable pageable);
}