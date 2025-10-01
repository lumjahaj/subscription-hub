package dev.lumjahaj.subscription.hub.tenancy.infra;

import dev.lumjahaj.subscription.hub.tenancy.domain.Tenant;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TenantRepositoryImpl implements TenantRepository {

    private final TenantJpaRepository jpa;

    public TenantRepositoryImpl(TenantJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Tenant> findActiveById(String id) {
        return jpa.findByIdAndActiveTrue(id)
                .map(t -> new Tenant(t.getId(), t.getName(), t.isActive()));
    }
}
