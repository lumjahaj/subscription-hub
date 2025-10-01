package dev.lumjahaj.subscription.hub.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {
    Page<CustomerEntity> findByTenantId(String tenantId, Pageable pageable);
    Optional<CustomerEntity> findByTenantIdAndEmail(String tenantId, String email);
}
