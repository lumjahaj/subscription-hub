package dev.lumjahaj.subscription.hub.auth.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserJpaRepository extends JpaRepository<AppUserEntity, UUID> {
    Optional<AppUserEntity> findByTenantIdAndEmail(String tenantId, String email);
}
