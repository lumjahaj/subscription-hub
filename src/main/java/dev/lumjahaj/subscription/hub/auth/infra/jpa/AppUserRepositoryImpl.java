package dev.lumjahaj.subscription.hub.auth.infra.jpa;

import dev.lumjahaj.subscription.hub.auth.domain.AppUserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AppUserRepositoryImpl implements AppUserRepository {

    private final AppUserJpaRepository jpaRepository;

    public AppUserRepositoryImpl(AppUserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<AppUserEntity> findByTenantIdAndEmail(String tenantId, String email) {
        return jpaRepository.findByTenantIdAndEmail(tenantId, email);
    }

    @Override
    public AppUserEntity save(AppUserEntity user) {
        return jpaRepository.save(user);
    }
}
