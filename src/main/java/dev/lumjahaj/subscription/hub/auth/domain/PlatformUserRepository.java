package dev.lumjahaj.subscription.hub.auth.domain;

import dev.lumjahaj.subscription.hub.auth.infra.jpa.PlatformUserEntity;

import java.util.Optional;

public interface PlatformUserRepository {

    /**
     * Not tenant-scoped, deliberately: platform users belong to no tenant,
     * so email is globally unique here (uk_platform_user_email) rather than
     * per tenant as on app_user.
     */
    Optional<PlatformUserEntity> findByEmail(String email);

    /** Whether the platform has any administrator yet — see PlatformAdminBootstrap. */
    boolean anyExist();

    PlatformUserEntity save(PlatformUserEntity user);
}
