package dev.lumjahaj.subscription.hub.auth.infra.jpa;

import dev.lumjahaj.subscription.hub.auth.domain.PlatformUserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PlatformUserRepositoryImpl implements PlatformUserRepository {

    private final PlatformUserJpaRepository jpaRepository;

    public PlatformUserRepositoryImpl(PlatformUserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<PlatformUserEntity> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    public boolean anyExist() {
        return jpaRepository.count() > 0;
    }

    @Override
    public PlatformUserEntity save(PlatformUserEntity user) {
        return jpaRepository.save(user);
    }
}
