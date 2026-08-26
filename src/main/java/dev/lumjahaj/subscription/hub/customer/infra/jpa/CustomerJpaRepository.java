package dev.lumjahaj.subscription.hub.customer.infra.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {
    Optional<CustomerEntity> findByTenantIdAndEmail(String tenantId, String email);
    Page<CustomerEntity> findByTenantId(String tenantId, Pageable pageable);
}