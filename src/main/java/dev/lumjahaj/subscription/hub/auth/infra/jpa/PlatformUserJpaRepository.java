package dev.lumjahaj.subscription.hub.auth.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlatformUserJpaRepository extends JpaRepository<PlatformUserEntity, UUID> {
    Optional<PlatformUserEntity> findByEmail(String email);
}
