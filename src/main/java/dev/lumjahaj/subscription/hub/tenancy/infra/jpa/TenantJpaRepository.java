package dev.lumjahaj.subscription.hub.tenancy.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, String> {
    Optional<TenantEntity> findByIdAndActiveTrue(String id);
    List<TenantEntity> findByActiveTrue();
}
